# lex-transform-service

A Spring Boot service that ingests XML legal documents, validates them against an XSD schema,
transforms them to JSON using XSLT 3.0 (via Saxon-HE), and publishes normalised artifacts
suitable for downstream search indexing and AI/RAG pipelines.

---

## What does this service do?

```
POST XML  →  Validate (XSD)  →  Transform (XSLT)  →  Publish artifacts to disk
                                                         ├── judgment.json  (search index)
                                                         └── judgment.txt   (RAG corpus)
```

1. Submit an XML legal judgment via the REST API
2. The service validates it against `schemas/judgment.xsd` (required fields, date formats, namespace)
3. If valid, Saxon-HE runs `xslt/judgment-to-json.xslt` to produce a normalised JSON record
4. Both the JSON artifact and a plain-text RAG artifact are written to `output/{contentId}/`
5. Poll the status endpoint to see when processing is done and to read the artifacts

---

## Glossary

| Term | What it means |
|------|---------------|
| **XSD** | XML Schema Definition — defines which fields are required and what data types are valid. Think "form template". |
| **XSLT** | A stylesheet language — a declarative recipe that transforms XML into another format (here: JSON). |
| **Saxon-HE** | Free Java library that executes XSLT 3.0 stylesheets. Supports `map{}`, `array{}`, and `fn:serialize()` for pure-XSLT JSON generation. |
| **RAG** | Retrieval-Augmented Generation — the plain-text artifact feeds AI embedding pipelines for semantic search. |
| **content_id** | Unique document identifier (e.g. `FR-2024-CA-000123`), extracted from `<content_id>` in the XML. |
| **Artifact** | The output files produced per document: `judgment.json` and `judgment.txt`. |

---

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker + Compose | any | `docker --version` (optional) |

---

## Run locally

```bash
# Build (skip tests for speed)
mvn clean package -DskipTests -o

# Run
APP_OUTPUT_DIR=./output mvn spring-boot:run -o

# The service starts on http://localhost:8080
# Artifacts are written to ./output/{contentId}/
```

> **Note:** The `-o` flag uses cached Maven dependencies (offline mode). Remove it if you have
> internet access to Maven Central.

---

## Run with Docker Compose

```bash
docker compose up --build
```

- Service: http://localhost:8080
- Artifacts volume-mounted at `./output/`
- Sample files volume-mounted read-only from `./sample-data/`

---

## Configuration

All settings can be overridden via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_OUTPUT_DIR` | `./output` | Root directory for published artifacts |
| `APP_CONCURRENCY_POOL_SIZE` | `4` | Simultaneous documents in the processing pipeline |
| `APP_CONCURRENCY_QUEUE_CAPACITY` | `100` | Queue depth before back-pressure kicks in |
| `SERVER_PORT` | `8080` | HTTP port |

---

## API Reference

Base path: `/api/v1/documents`

### Submit one document

```bash
curl -X POST http://localhost:8080/api/v1/documents \
     -H "Content-Type: application/xml" \
     --data-binary @sample-data/sample1.xml
```

Response — `202 Accepted` (new submission):
```json
{
  "contentId": "FR-2024-CA-000123",
  "status": "PENDING",
  "submittedAt": "2024-07-21T10:00:00Z",
  "message": "Document accepted for processing"
}
```

Response — `200 OK` (identical content already published):
```json
{
  "contentId": "FR-2024-CA-000123",
  "status": "PUBLISHED",
  "submittedAt": "2024-07-21T10:00:00Z",
  "message": "Already published with identical content — no reprocessing needed"
}
```

---

### Poll status and retrieve artifacts

```bash
curl http://localhost:8080/api/v1/documents/FR-2024-CA-000123
```

Response when done — `200 OK`, `status: PUBLISHED`:
```json
{
  "contentId": "FR-2024-CA-000123",
  "status": "PUBLISHED",
  "submittedAt": "2024-07-21T10:00:00Z",
  "processedAt": "2024-07-21T10:00:01Z",
  "artifacts": {
    "jsonContent": "{ \"content_id\": \"FR-2024-CA-000123\", ... }",
    "plainText": "Le litige porte sur... Considérant que..."
  }
}
```

Response when validation failed — `200 OK`, `status: INVALID`:
```json
{
  "contentId": "FR-2024-INVALID-001",
  "status": "INVALID",
  "submittedAt": "...",
  "processedAt": "...",
  "validationErrors": [
    "ERROR [line 7, col 24]: cvc-complex-type.2.4.a: Invalid content — expected 'court'",
    "ERROR [line 8, col 34]: cvc-datatype-valid.1.2.1: 'March 12' is not a valid xs:date"
  ]
}
```

---

### List all documents

```bash
curl http://localhost:8080/api/v1/documents
```

Returns an array of status records (no artifact content).

---

### Batch upload — multiple files

```bash
curl -X POST http://localhost:8080/api/v1/documents/batch \
     -F "files=@sample-data/sample1.xml" \
     -F "files=@sample-data/sample2.xml"
```

Response — `202 Accepted`:
```json
{
  "submittedCount": 2,
  "acceptedContentIds": ["FR-2024-CA-000123", "FR-2024-TGI-000456"],
  "message": "Batch accepted — 2 document(s) queued for processing"
}
```

---

### Batch from a server-side folder

```bash
curl -X POST http://localhost:8080/api/v1/documents/batch/folder \
     -H "Content-Type: application/json" \
     -d '{"folderPath": "./sample-data"}'
```

Scans the top-level of the folder for `.xml` files and submits each one.

---

### Health and metrics

```bash
# Liveness + readiness
curl http://localhost:8080/actuator/health

# Prometheus scrape endpoint
curl http://localhost:8080/actuator/prometheus | grep "^documents_"
```

Available metrics:

| Metric | Description |
|--------|-------------|
| `documents_submitted_total` | Every new document received |
| `documents_published_total` | Every successful transform + publish |
| `documents_invalid_total` | Every XSD validation failure |
| `documents_failed_total` | Every unexpected processing error |
| `documents_duplicate_total` | Every re-submission skipped (same content) |
| `documents_transform_duration_seconds` | End-to-end pipeline duration histogram |

---

## Project structure

```
src/
├── main/
│   ├── java/com/lexisnexis/transform/
│   │   ├── LexTransformApplication.java             ← Spring Boot entry point
│   │   ├── controller/
│   │   │   └── DocumentController.java              ← All HTTP endpoints (thin layer)
│   │   ├── config/
│   │   │   ├── AppProperties.java                   ← Typed config (APP_* env vars)
│   │   │   └── ExecutorConfig.java                  ← Bounded thread pool setup
│   │   ├── domain/
│   │   │   ├── exception/
│   │   │   │   ├── DocumentProcessingException.java ← Base exception (carries HTTP status)
│   │   │   │   ├── DocumentValidationException.java ← XSD failure — 422, carries error list
│   │   │   │   ├── DocumentTransformException.java  ← Saxon failure — 500
│   │   │   │   ├── ArtifactStorageException.java    ← I/O failure — 500
│   │   │   │   └── GlobalExceptionHandler.java      ← @RestControllerAdvice error mapper
│   │   │   ├── model/
│   │   │   │   └── DocumentRecord.java              ← Tracks one document's lifecycle
│   │   │   ├── resources/
│   │   │   │   ├── BatchFolderRequest.java          ← Request DTO: folder path
│   │   │   │   ├── BatchSubmitResponse.java         ← Response DTO: batch result
│   │   │   │   ├── DocumentStatusResponse.java      ← Response DTO: status + artifacts
│   │   │   │   ├── DocumentSubmitResponse.java      ← Response DTO: submit acknowledgement
│   │   │   │   ├── constants/
│   │   │   │   │   └── ProcessingStatusEnum.java    ← PENDING/PROCESSING/PUBLISHED/INVALID/FAILED
│   │   │   │   └── error/
│   │   │   │       └── ErrorResponse.java           ← Uniform error payload
│   │   │   └── service/
│   │   │       ├── DocumentOrchestratorService.java ← Pipeline coordinator + idempotency
│   │   │       ├── DocumentValidationService.java   ← JAXP XSD validation
│   │   │       ├── DocumentTransformationService.java ← Saxon-HE XSLT + XPath
│   │   │       └── DocumentPublishingService.java   ← Write artifacts to disk
│   │   └── metrics/
│   │       └── DocumentTransformMetrics.java        ← 6 Micrometer counters + 1 timer
│   └── resources/
│       ├── application.yml                          ← Externalised config
│       ├── schemas/judgment.xsd                     ← XSD validation contract
│       └── xslt/judgment-to-json.xslt              ← XSLT 3.0 transformation recipe
├── test/
│   └── java/com/lexisnexis/transform/
│       ├── integration/
│       │   └── DocumentControllerTest.java          ← 5 full Spring Boot integration tests
│       └── service/
│           ├── DocumentValidationServiceTest.java   ← 3 unit tests for XSD validation
│           └── DocumentTransformationServiceTest.java ← 2 unit tests for XSLT output
sample-data/
├── sample1.xml                                      ← Court of Appeal, Paris (2 citations)
└── sample2.xml                                      ← Tribunal, Lyon (1 citation, 6 paragraphs)
output/
└── FR-2024-CA-000123/
    ├── judgment.json                                ← Normalised JSON (smoke test output)
    └── judgment.txt                                 ← Plain text for RAG (smoke test output)
Dockerfile
docker-compose.yml
SOLUTION.md                                          ← Full design explanation
```

---

## Running the tests

```bash
mvn test -o
```

10 tests covering:

- **XSD validation** (`DocumentValidationServiceTest`): valid document passes, missing required field fails, invalid date format fails
- **XSLT transformation** (`DocumentTransformationServiceTest`): output contains expected JSON fields, plain-text extraction produces clean prose
- **HTTP layer** (`DocumentControllerTest`): submit → 202, poll → PUBLISHED with artifacts, duplicate → 200, unknown ID → 404, batch folder submission

---

## Processing pipeline

```
POST /api/v1/documents
     │
     ▼
DocumentController
  – read body → call orchestrator → return 202 immediately
     │
     ▼
DocumentOrchestratorService.submitDocumentForProcessing()
  – compute SHA-256 of XML bytes  (idempotency key)
  – extract <content_id> from XML
  – check ConcurrentHashMap for duplicate (same ID + same hash) → return existing if found
  – create DocumentRecord (status: PENDING), store in map
  – submit to thread pool via CompletableFuture.runAsync()
     │
     ▼  (worker thread — document-processor-N)
  orchestrateDocumentPipeline()
     │
     ├─ DocumentValidationService.validateXmlAgainstSchema()
     │    JAXP SchemaFactory (compiled once) + new Validator per document
     │    CollectingXsdErrorHandler gathers ALL errors before throwing
     │    → throws DocumentValidationException  → markAsInvalid(errors)
     │
     ├─ DocumentTransformationService.transformXmlDocumentToJson()
     │    Saxon-HE XsltExecutable (compiled once) + new XsltTransformer per document
     │    judgment-to-json.xslt produces normalised JSON string
     │
     ├─ DocumentTransformationService.extractPlainTextFromXmlDocument()
     │    Saxon-HE XPath: string-join over all <p> elements → plain text
     │
     ├─ DocumentPublishingService.publishDocumentArtifacts()
     │    Creates output/{contentId}/
     │    Writes judgment.json  (TRUNCATE_EXISTING — idempotent)
     │    Writes judgment.txt   (TRUNCATE_EXISTING — idempotent)
     │
     └─ documentRecord.markAsPublished(jsonPath, txtPath)
          Metrics: published++, duration recorded
```

See [SOLUTION.md](SOLUTION.md) for a detailed walkthrough of every file including the XSD,
XSLT, sample data, and published artifacts.
