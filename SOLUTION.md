# Solution Overview

## Problem

LexisNexis receives legal judgments as raw XML files. These need to be converted into structured JSON so downstream search engines and AI systems can index and query them. This service handles that conversion automatically.

---

## How It Works

A client submits an XML judgment via a REST API call. The service validates it against an XSD schema, transforms it to JSON using an XSLT stylesheet, and writes two output files to disk — a JSON record for search indexing and a plain-text file for AI/RAG pipelines. The HTTP call returns immediately (202 Accepted) and the processing happens in the background. The client polls a status endpoint to get the result.

---

## Key Files Explained

### `schemas/judgment.xsd`
The validation contract. Defines which fields are required (`content_id`, `title`, `court`, `jurisdiction`, `decision_date`, at least one section with paragraphs), what formats are enforced (`decision_date` must be a real date like `2024-03-12`), and what namespace all elements must use (`urn:lex:content:1`). Validation runs before the XSLT so the stylesheet never receives malformed input.

### `xslt/judgment-to-json.xslt`
The transformation recipe. Uses XSLT 3.0 with XPath 3.1 `map{}` and `array{}` constructors to build the JSON structure directly in the stylesheet — no Java JSON library involved. Saxon-HE's `fn:serialize()` turns the in-memory map/array into a formatted JSON string. The `full_text` field concatenates all paragraph texts into one clean string, which is what AI embedding pipelines use for semantic search.

### `sample-data/`
Two sample XML judgments (`sample1.xml` and `sample2.xml`) representing French court rulings. They demonstrate that the schema and XSLT handle varying numbers of citations, parties, sections, and paragraphs without any code changes.

### `output/FR-2024-CA-000123/`
The actual output produced when `sample1.xml` is submitted. Contains two files:
- `judgment.json` — the normalised JSON record ready for a search index (Elasticsearch, OpenSearch)
- `judgment.txt` — plain prose of all paragraphs, ready for an AI embedding pipeline

---

## Processing Pipeline

1. **Validate** — `DocumentValidationService` compiles the XSD once at startup (expensive). For each document, it creates a fresh `Validator` (not thread-safe) and collects all schema errors before throwing — so the caller gets every problem in one response, not just the first one.

2. **Transform** — `DocumentTransformationService` compiles the XSLT once at startup via Saxon-HE (expensive). For each document, it creates a fresh `XsltTransformer` (not thread-safe), runs the stylesheet, and returns a JSON string. It also runs an XPath `string-join` expression to extract the plain text.

3. **Publish** — `DocumentPublishingService` creates a directory named after the `content_id` and writes both files with `TRUNCATE_EXISTING` so re-publishing the same document overwrites cleanly.

4. **Record** — `DocumentOrchestratorService` marks the `DocumentRecord` as `PUBLISHED` and records the artifact paths and pipeline duration.

---

## Document Lifecycle

A `DocumentRecord` moves through these states:

- `PENDING` — accepted, waiting for a worker thread
- `PROCESSING` — pipeline is running
- `PUBLISHED` — done, artifacts available
- `INVALID` — XSD validation failed (caller's problem — errors are returned)
- `FAILED` — unexpected internal error (retryable)

The distinction between `INVALID` and `FAILED` matters for monitoring: a spike in `INVALID` means bad input upstream; a spike in `FAILED` means something broke inside the service.

---

## Idempotency

Before processing, the orchestrator computes a SHA-256 hash of the XML bytes and checks whether a record with the same `content_id` and the same hash already exists and is `PUBLISHED`. If so, it skips re-processing and returns the existing record with `200 OK`. If the same `content_id` is resubmitted with different content (a corrected document), the hash won't match and it processes normally.

---

## Configuration

All runtime settings are externalised via environment variables so the same Docker image can run in any environment without a rebuild:

| Variable | Default | Purpose |
|----------|---------|---------|
| `APP_OUTPUT_DIR` | `./output` | Where artifacts are written |
| `APP_CONCURRENCY_POOL_SIZE` | `4` | Worker thread count |
| `APP_CONCURRENCY_QUEUE_CAPACITY` | `100` | Max queued documents |

---

## Technology Decisions

**XSLT 3.0 over Jackson** — The assignment specified XSLT. It also has an architectural benefit: the stylesheet is the single source of truth for the output JSON shape. Adding a field means editing the `.xslt` file, not changing Java code or DTO classes.

**Saxon-HE** — The only freely available XSLT 3.0 engine. The `map{}` / `array{}` / `fn:serialize()` features used by the stylesheet require version 3.0; XSLT 1.0 and 2.0 cannot produce JSON this way.

**JAXP for XSD validation** — Built into the JDK, no extra dependency. The `SchemaFactory` / `Validator` pair gives full `SAXParseException` line/column reporting via a custom `ErrorHandler`.

**`CompletableFuture` + `ThreadPoolTaskExecutor`** — Keeps the HTTP layer non-blocking. The thread pool is bounded (size and queue capacity configurable) so the service degrades gracefully under load rather than spawning unlimited threads.

**`ConcurrentHashMap` in-memory store** — Appropriate for the scope of this assignment. In production this would be a persistent store (PostgreSQL, Redis) to survive restarts and to support horizontal scaling.
