# LexisNexis XML Transform Service — Solution Overview

This document explains every part of the solution: how XML enters the system,
how it is validated, how XSLT turns it into JSON, how artifacts are written to
disk, and how the output is shaped to serve downstream search and RAG pipelines.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [End-to-End Flow](#2-end-to-end-flow)
3. [The Schema Contract — `schemas/judgment.xsd`](#3-the-schema-contract--schemasjudgmentxsd)
4. [Sample Input — `sample-data/`](#4-sample-input--sample-data)
5. [XSD Validation — `DocumentValidationService`](#5-xsd-validation--documentvalidationservice)
6. [XSLT Transformation — `xslt/judgment-to-json.xslt`](#6-xslt-transformation--xsltjudgment-to-jsonxslt)
7. [Saxon-HE — How the Transformation Actually Runs](#7-saxon-he--how-the-transformation-actually-runs)
8. [Artifact Publishing — `DocumentPublishingService`](#8-artifact-publishing--documentpublishingservice)
9. [Published Output — `output/`](#9-published-output--output)
10. [Orchestration — `DocumentOrchestratorService`](#10-orchestration--documentorchestratorservice)
11. [HTTP API — `DocumentController`](#11-http-api--documentcontroller)
12. [Error Handling — `GlobalExceptionHandler`](#12-error-handling--globalexceptionhandler)
13. [Observability — Metrics and Health](#13-observability--metrics-and-health)
14. [Configuration — `AppProperties`](#14-configuration--appproperties)
15. [Assignment Task Mapping](#15-assignment-task-mapping)
16. [Technology Decisions](#16-technology-decisions)

---

## 1. Problem Statement

LexisNexis ingests legal judgments that arrive as XML documents from courts and
legal databases across multiple jurisdictions. The raw XML format is not
directly searchable — it must be converted into a normalised, flat JSON record
that downstream search indices and RAG (Retrieval-Augmented Generation) AI
systems can ingest efficiently.

The three assignment tasks map directly to the three main concerns:

| Task | Concern |
|------|---------|
| Task 1 | Single-document REST API with async processing and status polling |
| Task 2 | Batch ingestion (multi-file upload and server-side folder scan) |
| Task 3 | Cloud-readiness: metrics, health endpoints, containerisation |

---

## 2. End-to-End Flow

```
CLIENT
  │
  │  POST /api/v1/documents
  │  Content-Type: application/xml
  │  Body: raw XML judgment
  │
  ▼
DocumentController
  │  Reads body, calls orchestrator, returns 202 Accepted immediately
  │
  ▼
DocumentOrchestratorService.submitDocumentForProcessing()
  │
  ├─ Compute SHA-256 of XML bytes → contentHash
  ├─ Extract <content_id> from XML text → contentId
  ├─ Check ConcurrentHashMap for duplicate (same contentId + same hash)
  │   └─ If found and PUBLISHED → return existing record (idempotent, 200 OK)
  ├─ Create DocumentRecord(contentId, contentHash) with status PENDING
  ├─ Store in ConcurrentHashMap
  └─ Submit to background thread pool via CompletableFuture.runAsync()
       │
       ▼  (worker thread)
       orchestrateDocumentPipeline()
         │
         ├─ [Step 1] DocumentValidationService.validateXmlAgainstSchema()
         │    ├─ JAXP SchemaFactory loads compiled XSD (once at startup)
         │    ├─ Creates fresh Validator for this document
         │    ├─ CollectingXsdErrorHandler gathers ALL errors before returning
         │    └─ If errors found → throw DocumentValidationException(List<String>)
         │         └─ catch → markAsInvalid(errors), metric: invalid++
         │
         ├─ [Step 2] DocumentTransformationService
         │    ├─ transformXmlDocumentToJson()
         │    │    └─ Saxon-HE runs judgment-to-json.xslt → returns JSON string
         │    └─ extractPlainTextFromXmlDocument()
         │         └─ Saxon-HE XPath string-join over all <p> → returns plain text
         │
         ├─ [Step 3] DocumentPublishingService.publishDocumentArtifacts()
         │    ├─ Creates output/{contentId}/ directory
         │    ├─ Writes judgment.json (normalised JSON)
         │    └─ Writes judgment.txt  (plain text for RAG)
         │
         └─ [Step 4] documentRecord.markAsPublished(jsonPath, txtPath)
              └─ metric: published++, duration recorded

CLIENT POLLS
  GET /api/v1/documents/{contentId}
  → returns status + JSON artifact + plain-text artifact when PUBLISHED
```

---

## 3. The Schema Contract — `schemas/judgment.xsd`

**File:** `src/main/resources/schemas/judgment.xsd`

An XSD (XML Schema Definition) is a formal contract — a "form template" that
specifies exactly what a valid XML document must contain. The XSD is loaded
once at service startup and compiled into a `javax.xml.validation.Schema`
object that is reused for every validation call.

### Namespace

```xml
targetNamespace="urn:lex:content:1"
```

Every element in a valid judgment XML must be in this namespace. The `urn:lex`
prefix makes it a Uniform Resource Name — it identifies the LexisNexis content
version 1 vocabulary and prevents collisions with other XML vocabularies.

### Document Structure

```
<judgment xmlns="urn:lex:content:1">          ← root element, REQUIRED
  <header>                                     ← REQUIRED
    <content_id>   xs:string   </content_id>  ← REQUIRED — unique document ID
    <title>        xs:string   </title>        ← REQUIRED — human-readable title
    <court>        xs:string   </court>        ← REQUIRED — issuing court name
    <jurisdiction> xs:string   </jurisdiction> ← REQUIRED — ISO country code (e.g. "FR")
    <decision_date>xs:date     </decision_date>← REQUIRED — must be a real date (YYYY-MM-DD)
    <citations>                                ← OPTIONAL — zero or more citation references
      <citation type="ECLI">ECLI:FR:CA12345</citation>
      <citation type="NOR">NOR:ABCD1234567</citation>
    </citations>
    <parties>                                  ← OPTIONAL — zero or more parties
      <party role="appellant">Société ABC</party>
      <party role="respondent">M. Dupont</party>
    </parties>
  </header>
  <body>                                       ← REQUIRED
    <section type="facts">                     ← REQUIRED, one or more sections
      <p id="p1">text...</p>                  ← REQUIRED, one or more paragraphs per section
    </section>
  </body>
</judgment>
```

### What the XSD Enforces

| Rule | Detail |
|------|--------|
| Required fields | `content_id`, `title`, `court`, `jurisdiction`, `decision_date`, at least one `<section>`, at least one `<p>` per section |
| Date format | `decision_date` is `xs:date` — `2024-03-12` passes, `"March 12"` fails |
| Attribute presence | Every `<citation>` must have a `type` attribute; every `<party>` a `role`; every `<p>` an `id` attribute |
| Uniqueness | `<p id="...">` uses `xs:ID` — the validator checks that no two paragraphs share the same `id` in a document |
| Order | Child elements must appear in the declared `xs:sequence` order |
| Namespace | All elements must be in `urn:lex:content:1` |

### Why Validate Before Transforming?

The XSLT assumes a well-formed document. If `<decision_date>` is missing or
`<body>` contains no `<section>`, the XSLT silently produces empty fields
rather than raising an error. XSD validation catches the problem early,
returns all errors to the caller in a single response, and prevents
half-populated JSON records from reaching downstream systems.

---

## 4. Sample Input — `sample-data/`

Two sample XML documents are provided to demonstrate valid input.

### `sample-data/sample1.xml` — Court of Appeal, Paris

```xml
<?xml version="1.0" encoding="UTF-8"?>
<judgment xmlns="urn:lex:content:1">
    <header>
        <content_id>FR-2024-CA-000123</content_id>
        <title>Cour d'appel de Paris, 12 mars 2024, n° 20/01234</title>
        <court>Cour d'appel de Paris</court>
        <jurisdiction>FR</jurisdiction>
        <decision_date>2024-03-12</decision_date>
        <citations>
            <citation type="ECLI">ECLI:FR:CA12345</citation>
            <citation type="NOR">NOR:ABCD1234567</citation>
        </citations>
        <parties>
            <party role="appellant">Société ABC</party>
            <party role="respondent">M. Dupont</party>
        </parties>
    </header>
    <body>
        <section type="facts">
            <p id="p1">Le litige porte sur un différend contractuel...</p>
        </section>
        <section type="reasons">
            <p id="p2">Considérant que les parties ont conclu un accord...</p>
            <p id="p3">Attendu que la Société ABC n'a pas respecté...</p>
        </section>
        <section type="disposition">
            <p id="p4">Par ces motifs, la cour condamne la Société ABC...</p>
        </section>
    </body>
</judgment>
```

This document has two citations (`ECLI` and `NOR`), two parties, and three
sections with a total of four paragraphs.

### `sample-data/sample2.xml` — Tribunal judiciaire, Lyon

A second judgment (`content_id: FR-2024-TGI-000456`) with one citation
(`ECLI`), two parties (`plaintiff`/`defendant`), and six paragraphs across
three sections. Demonstrates that the schema and XSLT handle any number of
citations, parties, sections, and paragraphs without code changes.

### How to Use the Sample Files

**Single document submission:**
```bash
curl -X POST http://localhost:8080/api/v1/documents \
     -H "Content-Type: application/xml" \
     --data-binary @sample-data/sample1.xml
```

**Batch folder submission:**
```bash
curl -X POST http://localhost:8080/api/v1/documents/batch/folder \
     -H "Content-Type: application/json" \
     -d '{"folderPath": "./sample-data"}'
```

---

## 5. XSD Validation — `DocumentValidationService`

**File:** `src/main/java/com/lexisnexis/transform/domain/service/DocumentValidationService.java`

### Startup (`@PostConstruct`)

```java
SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
// Block external entity access — prevents XXE attacks
schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

compiledXsdSchema = schemaFactory.newSchema(new StreamSource(xsdInputStream));
```

Compiling the XSD is expensive (it parses the schema file and builds an
internal validation automaton). Doing it once at startup and storing the
compiled `Schema` object makes each validation call fast.

### Per-Request Validation

```java
CollectingXsdErrorHandler errorHandler = new CollectingXsdErrorHandler();
Validator schemaValidator = compiledXsdSchema.newValidator(); // new per request — NOT thread-safe
schemaValidator.setErrorHandler(errorHandler);
schemaValidator.validate(new StreamSource(new StringReader(xmlDocumentContent)));
```

`Validator` is not thread-safe, so a fresh one is created for each document.
The `CollectingXsdErrorHandler` overrides `warning()`, `error()`, and
`fatalError()` to append formatted messages (with line/column numbers) into an
`ArrayList<String>` rather than stopping at the first problem. This means the
caller receives the complete list of issues in one response — e.g.:

```json
{
  "errorCode": "VALIDATION_FAILED",
  "errorMessage": "Document validation failed with 2 error(s)"
}
```

If validation passes, `validateXmlAgainstSchema()` returns `void`. If it
fails, it throws `DocumentValidationException(List<String> validationErrors)`,
which carries the full error list and maps to HTTP 422 Unprocessable Entity.

---

## 6. XSLT Transformation — `xslt/judgment-to-json.xslt`

**File:** `src/main/resources/xslt/judgment-to-json.xslt`

### What is XSLT?

XSLT (Extensible Stylesheet Language Transformations) is a declarative
stylesheet language. Think of it as a recipe: given XML input, it produces
text output following the rules in the stylesheet. The key insight is that
no Java JSON library (`Jackson`, `Gson`, etc.) is needed — the JSON is
generated entirely by the XSLT engine.

### The Full Stylesheet

```xslt
<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:lex="urn:lex:content:1"
    exclude-result-prefixes="xs lex">

    <xsl:output method="text" encoding="UTF-8"/>

    <xsl:template match="/">
        <xsl:variable name="h" select="lex:judgment/lex:header"/>
        <xsl:variable name="b" select="lex:judgment/lex:body"/>

        <xsl:variable name="result" select="
            map {
                'content_id'    : string($h/lex:content_id),
                'title'         : string($h/lex:title),
                'court'         : string($h/lex:court),
                'jurisdiction'  : string($h/lex:jurisdiction),
                'decision_date' : string($h/lex:decision_date),

                'citations' : array {
                    for $c in $h/lex:citations/lex:citation
                    return map { 'type' : string($c/@type), 'value' : string($c) }
                },

                'parties' : array {
                    for $p in $h/lex:parties/lex:party
                    return map { 'role' : string($p/@role), 'name' : string($p) }
                },

                'paragraphs' : array {
                    for $p in $b/lex:section/lex:p
                    return map {
                        'id'      : string($p/@id),
                        'section' : string($p/parent::lex:section/@type),
                        'text'    : string($p)
                    }
                },

                'full_text' : string-join(
                    for $p in $b/lex:section/lex:p
                    return normalize-space(string($p)),
                    ' '
                )
            }
        "/>

        <xsl:value-of select="serialize($result, map { 'method' : 'json', 'indent' : true() })"/>
    </xsl:template>

</xsl:stylesheet>
```

### Line-by-Line Explanation

**`version="3.0"`** — XSLT 3.0 is required. XSLT 1.0 and 2.0 do not have the
`map {}` / `array {}` constructors or `fn:serialize()`. Saxon-HE is the only
freely available XSLT 3.0 engine.

**`xmlns:lex="urn:lex:content:1"`** — Declares the same namespace as the XML
documents and XSD. Without this prefix, XPath expressions like
`lex:judgment/lex:header` would not match any elements because the document
uses that namespace.

**`<xsl:output method="text"/>`** — The stylesheet produces plain text (the
raw JSON string), not XML. This suppresses the default XML declaration header
that XSLT would otherwise prepend.

**`<xsl:template match="/">`** — The single template that handles the document
root. XSLT starts processing here.

**`map { ... }`** (XPath 3.1) — A map constructor creates a JSON-like
key-value structure entirely within XPath. The map is held in memory as an
XDM (XQuery Data Model) value; no Java objects are involved.

**`array { for $c in ... return map {...} }`** — An array constructor with a
`for` expression iterates over all `<citation>` elements and builds one map
object per citation. If there are no citations, the array is empty (`[]`).
The same pattern applies to `parties` and `paragraphs`.

**`string($p/parent::lex:section/@type)`** — XPath axis navigation: from
paragraph `$p`, navigate to its parent `<section>` and read the `type`
attribute. This is how each paragraph record knows which section it belongs to
(`"facts"`, `"reasons"`, `"disposition"`).

**`full_text : string-join(..., ' ')`** — Concatenates all paragraph texts
into a single string separated by spaces. This is the field that downstream
AI/RAG systems use as the semantic search corpus — a vector embedding of this
string allows similarity search across thousands of judgments.

**`serialize($result, map { 'method' : 'json', 'indent' : true() })`** —
Saxon's built-in `fn:serialize()` converts the XDM map/array value into a
formatted JSON string with indentation. No Java code is involved in this
conversion.

---

## 7. Saxon-HE — How the Transformation Actually Runs

**File:** `src/main/java/com/lexisnexis/transform/domain/service/DocumentTransformationService.java`

Saxon-HE (Home Edition) is the free, open-source XSLT 3.0 and XQuery 3.1
processor from Saxonica. It is the only freely available engine that supports
the `map{}`, `array{}`, and `fn:serialize()` XPath 3.1 features used in the
stylesheet.

### Object Lifecycle

Saxon has three main objects with different thread-safety and creation costs:

| Object | Thread-safe? | Created | Cost |
|--------|-------------|---------|------|
| `Processor` | Yes | Once at startup | Low |
| `XsltExecutable` | Yes | Once at startup (compiled from .xslt file) | High |
| `XsltTransformer` | No | Once per document request | Low |

```java
// Startup (@PostConstruct) — runs once
saxonProcessor = new Processor(false);          // false = HE (not PE/EE)
XsltCompiler compiler = saxonProcessor.newXsltCompiler();
compiledXsltExecutable = compiler.compile(new StreamSource(xsltInputStream)); // compile once

// Per request — DocumentTransformationService.transformXmlDocumentToJson()
XdmNode parsedDoc = saxonProcessor.newDocumentBuilder()
                                  .build(new StreamSource(new StringReader(xmlContent)));
XsltTransformer transformer = compiledXsltExecutable.load(); // new per request
StringWriter output = new StringWriter();
Serializer serializer = saxonProcessor.newSerializer(output);
transformer.setInitialContextNode(parsedDoc);
transformer.setDestination(serializer);
transformer.transform();
return output.toString(); // the JSON string
```

Compiling the XSLT stylesheet parses the `.xslt` file and builds an internal
bytecode representation. This is expensive and must happen only once. Loading
a transformer from the compiled executable is cheap and happens per document.

### Plain-Text Extraction

The second method, `extractPlainTextFromXmlDocument()`, uses Saxon's XPath
API directly rather than running the full XSLT:

```java
xpathCompiler.declareNamespace("lex", "urn:lex:content:1");
xpathSelector = xpathCompiler
    .compile("string-join(//lex:body/lex:section/lex:p/normalize-space(string(.)), ' ')")
    .load();
xpathSelector.setContextItem(parsedDoc);
return xpathSelector.evaluateSingle().getStringValue();
```

The XPath `string-join(//lex:body/lex:section/lex:p/normalize-space(string(.)), ' ')`
collects all paragraph texts, trims whitespace from each, and joins them with
a single space. The result is written to `judgment.txt` as a standalone file
that RAG pipelines can ingest without parsing JSON.

---

## 8. Artifact Publishing — `DocumentPublishingService`

**File:** `src/main/java/com/lexisnexis/transform/domain/service/DocumentPublishingService.java`

### Startup (`@PostConstruct`)

```java
artifactOutputRootDirectory = Path.of(appProperties.getOutputDir()).toAbsolutePath().normalize();
Files.createDirectories(artifactOutputRootDirectory);
```

Creates the root output directory on startup if it does not exist. The path
defaults to `./output` relative to the working directory and can be overridden
with the `APP_OUTPUT_DIR` environment variable.

### Writing Artifacts

For each document, a subdirectory is created using the `content_id` as the
name (with characters unsafe for filesystems replaced by `_`):

```
output/
  FR-2024-CA-000123/
    judgment.json    ← normalised JSON produced by the XSLT
    judgment.txt     ← plain-text concatenation of all paragraphs (for RAG)
```

Both files are written with `TRUNCATE_EXISTING` so re-processing the same
`content_id` overwrites rather than appending.

The method returns a `PublishedArtifactPaths` value object carrying the
absolute paths of the two written files. These paths are stored on
`DocumentRecord` and returned in the status API response.

---

## 9. Published Output — `output/`

The `output/` directory contains the real artifacts produced during smoke
testing. They demonstrate exactly what the pipeline produces from `sample1.xml`.

### `output/FR-2024-CA-000123/judgment.json`

```json
{
  "content_id": "FR-2024-CA-000123",
  "jurisdiction": "FR",
  "parties": [
    { "name": "Société ABC", "role": "appellant" },
    { "name": "M. Dupont",   "role": "respondent" }
  ],
  "paragraphs": [
    { "section": "facts",       "text": "Le litige porte sur...", "id": "p1" },
    { "section": "reasons",     "text": "Considérant que...",     "id": "p2" },
    { "section": "reasons",     "text": "Attendu que...",         "id": "p3" },
    { "section": "disposition", "text": "Par ces motifs...",      "id": "p4" }
  ],
  "court": "Cour d'appel de Paris",
  "citations": [
    { "value": "ECLI:FR:CA12345",  "type": "ECLI" },
    { "value": "NOR:ABCD1234567",  "type": "NOR"  }
  ],
  "decision_date": "2024-03-12",
  "title": "Cour d'appel de Paris, 12 mars 2024, n° 20/01234",
  "full_text": "Le litige porte sur... Considérant que... Attendu que... Par ces motifs..."
}
```

This is the **search index artifact**. A downstream Elasticsearch or
OpenSearch index can ingest this directly — each top-level field becomes an
indexed field, `paragraphs` becomes a nested object array, and `full_text`
is the field used for full-text search across the corpus.

### `output/FR-2024-CA-000123/judgment.txt`

```
Le litige porte sur... Considérant que... Attendu que... Par ces motifs...
```

This is the **RAG artifact**. A language model embedding pipeline reads this
file, produces a vector, and stores it in a vector database (Pinecone,
pgvector, Weaviate). At query time, a user's question is embedded into the
same vector space, and the nearest judgment texts are retrieved as context for
the LLM answer.

The plain-text file intentionally contains no JSON syntax, XML tags, or
metadata — just the judgment text — because embedding models produce better
semantic representations when the input is clean prose.

---

## 10. Orchestration — `DocumentOrchestratorService`

**File:** `src/main/java/com/lexisnexis/transform/domain/service/DocumentOrchestratorService.java`


This is the coordinator. It owns the in-memory document index and manages the
lifecycle of each document from submission through completion.

### Idempotency via SHA-256

```java
String contentHash = computeSha256Hash(xmlDocumentContent);      // SHA-256 of raw XML bytes
String contentId   = extractContentIdFromXml(xmlDocumentContent); // parse <content_id> by text search

DocumentRecord existing = documentRecordIndex.get(contentId);
if (existing != null
    && existing.getProcessingStatus() == PUBLISHED
    && contentHash.equals(existing.getContentHash())) {
    return existing; // duplicate — return existing record, no re-processing
}
```

A duplicate is defined as: same `content_id` **and** same SHA-256 hash. If the
same `content_id` is submitted with different content (corrected document), it
is treated as a new submission and replaces the existing record. This design
prevents re-processing costs for identical re-submissions while allowing
corrections to replace prior versions.

### Async Pipeline

```java
CompletableFuture.runAsync(
    () -> orchestrateDocumentPipeline(newDocumentRecord, xmlDocumentContent),
    documentProcessingExecutor);
```

The HTTP request returns immediately (202 Accepted) after registering the
record. The four-stage pipeline (validate → transform → publish → record)
runs on a `ThreadPoolTaskExecutor` with configurable pool size (default:
4 threads, queue capacity: 100 documents).

### Status Transitions

```
PENDING → PROCESSING → PUBLISHED   (success path)
                     → INVALID     (XSD validation failed — caller error)
                     → FAILED      (transform or I/O error — service error)
```

`INVALID` means the submitted XML was malformed relative to the schema. The
errors are stored on the record and returned in the status response so the
submitter knows exactly what to fix.

`FAILED` means an unexpected internal error occurred (Saxon crashed, disk
full, etc.). These are retryable — re-submitting the document starts a
new pipeline run.

---

## 11. HTTP API — `DocumentController`

**File:** `src/main/java/com/lexisnexis/transform/controller/DocumentController.java`

The controller is intentionally thin — it handles only HTTP concerns and
delegates all business logic to `DocumentOrchestratorService`.

### Endpoints

#### `POST /api/v1/documents`

Accepts a single XML document in the request body.

- Returns `202 Accepted` + `DocumentSubmitResponse` when queued for processing
- Returns `200 OK` + `DocumentSubmitResponse` when the identical document was
  already successfully published (idempotent re-submission)

```bash
curl -X POST http://localhost:8080/api/v1/documents \
     -H "Content-Type: application/xml" \
     --data-binary @sample-data/sample1.xml
```

Response:
```json
{
  "contentId": "FR-2024-CA-000123",
  "processingStatus": "PENDING",
  "message": "Document accepted for processing"
}
```

#### `GET /api/v1/documents/{contentId}`

Polls the status of a submitted document.

- Returns `200 OK` + `DocumentStatusResponse` when found (any status)
- Returns `404 Not Found` when the `content_id` has not been submitted

When `processingStatus` is `PUBLISHED`, the response includes:
- `normalizedJson` — the full JSON artifact content
- `plainText` — the full plain-text artifact content

```bash
curl http://localhost:8080/api/v1/documents/FR-2024-CA-000123
```

#### `GET /api/v1/documents`

Lists all submitted documents (summary only, no artifact content).

#### `POST /api/v1/documents/batch` (multipart)

Accepts multiple XML files uploaded as multipart form data. Returns immediately
with a count of accepted documents.

```bash
curl -X POST http://localhost:8080/api/v1/documents/batch \
     -F "files=@sample-data/sample1.xml" \
     -F "files=@sample-data/sample2.xml"
```

#### `POST /api/v1/documents/batch/folder`

Accepts a server-side folder path. Scans for `.xml` files and submits each
one. Useful for bulk ingestion of pre-loaded document sets.

```bash
curl -X POST http://localhost:8080/api/v1/documents/batch/folder \
     -H "Content-Type: application/json" \
     -d '{"folderPath": "./sample-data"}'
```

---

## 12. Error Handling — `GlobalExceptionHandler`

**File:** `src/main/java/com/lexisnexis/transform/domain/exception/GlobalExceptionHandler.java`

All `DocumentProcessingException` subclasses carry their own HTTP status code
and error code. The handler reads these from the exception rather than using
`instanceof` checks:

| Exception | HTTP Status | Error Code | When |
|-----------|------------|------------|------|
| `DocumentValidationException` | 422 Unprocessable Entity | `VALIDATION_FAILED` | XSD schema violations |
| `DocumentTransformException` | 500 Internal Server Error | `TRANSFORM_FAILED` | Saxon XSLT error |
| `ArtifactStorageException` | 500 Internal Server Error | `STORAGE_FAILED` | File I/O error |
| Any other `Exception` | 500 Internal Server Error | `INTERNAL_ERROR` | Unexpected error |

The error response shape is always consistent:

```json
{
  "errorCode": "VALIDATION_FAILED",
  "errorMessage": "Document validation failed with 2 error(s)"
}
```

Note: `DocumentValidationException` is only surfaced to the HTTP layer if
validation is called synchronously. In the normal async pipeline, the
exception is caught inside the worker thread and stored on `DocumentRecord`
as validation errors — it does not propagate to the HTTP layer.

---

## 13. Observability — Metrics and Health

**File:** `src/main/java/com/lexisnexis/transform/metrics/DocumentTransformMetrics.java`

Six Micrometer counters and one timer are exposed at `/actuator/prometheus`:


| Metric | Type | Description |
|--------|------|-------------|
| `documents_submitted_total` | Counter | Every new submission (excluding duplicates) |
| `documents_published_total` | Counter | Successfully processed documents |
| `documents_invalid_total` | Counter | XSD validation failures |
| `documents_failed_total` | Counter | Transform or storage failures |
| `documents_duplicate_total` | Counter | Idempotent re-submissions |
| `documents_transform_duration` | Timer | End-to-end pipeline duration |

### Health

Spring Boot Actuator exposes built-in health at `/actuator/health`. The service
is stateless between restarts (no persistent queue, no database) so the default
liveness and readiness checks cover the main failure modes.

### Accessing Metrics

```bash
# Prometheus scrape endpoint
curl http://localhost:8080/actuator/prometheus

# Health check
curl http://localhost:8080/actuator/health

# Info endpoint
curl http://localhost:8080/actuator/info
```

---

## 14. Configuration — `AppProperties`

**File:** `src/main/java/com/lexisnexis/transform/config/AppProperties.java`

All runtime parameters are externalised and can be overridden via environment
variables (standard Spring Boot `APP_*` → `app.*` binding):

| Property | Default | Env Variable | Description |
|----------|---------|-------------|-------------|
| `app.output-dir` | `./output` | `APP_OUTPUT_DIR` | Root directory for published artifacts |
| `app.concurrency.pool-size` | `4` | `APP_CONCURRENCY_POOL_SIZE` | Worker thread count |
| `app.concurrency.queue-capacity` | `100` | `APP_CONCURRENCY_QUEUE_CAPACITY` | Max queued documents |
| `app.xslt.path` | `classpath:xslt/judgment-to-json.xslt` | `APP_XSLT_PATH` | XSLT stylesheet location |
| `app.schema.path` | `classpath:schemas/judgment.xsd` | `APP_SCHEMA_PATH` | XSD schema location |

### Docker / Kubernetes

```yaml
# docker-compose
environment:
  - APP_OUTPUT_DIR=/data/output
  - APP_CONCURRENCY_POOL_SIZE=8

# Kubernetes deployment
env:
  - name: APP_OUTPUT_DIR
    value: /data/output
  - name: APP_CONCURRENCY_POOL_SIZE
    valueFrom:
      configMapKeyRef:
        name: lex-transform-config
        key: pool-size
```

---

## 15. Assignment Task Mapping

### Task 1 — Single Document Processing

| Requirement | Implementation |
|-------------|---------------|
| REST endpoint accepts XML | `POST /api/v1/documents` (`application/xml`) |
| XSD validation | `DocumentValidationService` + JAXP `SchemaFactory` |
| XSLT transformation | `DocumentTransformationService` + Saxon-HE |
| Async processing | `CompletableFuture.runAsync()` + `ThreadPoolTaskExecutor` |
| Status polling | `GET /api/v1/documents/{contentId}` |
| Artifact retrieval | JSON + plain-text returned in status response |
| Idempotency | SHA-256 hash deduplication in `DocumentOrchestratorService` |

### Task 2 — Batch Processing

| Requirement | Implementation |
|-------------|---------------|
| Multiple file upload | `POST /api/v1/documents/batch` (multipart) |
| Server-side folder scan | `POST /api/v1/documents/batch/folder` |
| Parallel processing | Each document submitted to the same thread pool independently |

### Task 3 — Production Readiness

| Requirement | Implementation |
|-------------|---------------|
| Metrics | `DocumentTransformMetrics` — 6 Micrometer metrics at `/actuator/prometheus` |
| Health checks | Spring Boot Actuator `/actuator/health` |
| Containerisation | `Dockerfile` (multi-stage, distroless base) |
| Cloud config | All config externalised via `APP_*` env variables |
| Security | XXE prevention in `SchemaFactory`, no path traversal in artifact storage |

---

## 16. Technology Decisions

| Choice | Alternative | Reason |
|--------|------------|--------|
| XSLT 3.0 + Saxon-HE | Jackson + manual mapping | Assignment requirement; also avoids maintaining Java mapping code that mirrors the schema — the XSLT is the single source of truth for the output shape |
| Saxon-HE 12.4 | Saxon-PE/EE | Free edition; `map{}`, `array{}`, `fn:serialize()` all available in HE |
| Spring Boot 3.4.5 | 3.5.x | 3.5.11 had missing Micrometer jars in the offline Maven cache; 3.4.5 was fully cached |
| Java 21 | Java 17 | Assignment requirement; virtual threads available if needed for future I/O scaling |
| `CompletableFuture` + `ThreadPoolTaskExecutor` | Spring `@Async` | Explicit executor injection via `@Qualifier`; avoids ambiguity when multiple executors exist |
| JAXP `SchemaFactory` | Saxon XSD validation | JAXP is part of the JDK — no extra dependency; standard `ErrorHandler` interface |
| `CollectingXsdErrorHandler` | Fail-fast on first error | Submitters receive the complete list of schema violations in one API call rather than discovering issues one at a time |
| `ConcurrentHashMap` in-memory index | Redis / PostgreSQL | Appropriate scope for the assignment; in production this would be a persistent store to survive restarts |
