# lex-transform-service

A Spring Boot service that ingests XML legal documents, validates them against an XSD schema, transforms them to JSON using XSLT 3.0 (via Saxon-HE), and writes normalised artifacts to disk for downstream search indexing and AI/RAG pipelines.

---

## Prerequisites

- Java 21
- Maven 3.9+
- Docker (optional)

---

## Run locally

```bash
mvn clean package -DskipTests -o
mvn spring-boot:run -o
```

Service starts on `http://localhost:8080`. Artifacts are written to `./output/{contentId}/`.

Remove `-o` (offline flag) if you have internet access to Maven Central.

---

## Run with Docker

```bash
docker compose up --build
```

---

## API

Base path: `/api/v1/documents`

### Submit a document

```bash
curl -X POST http://localhost:8080/api/v1/documents \
     -H "Content-Type: application/xml" \
     --data-binary @sample-data/sample1.xml
```

Returns `202 Accepted` when queued, or `200 OK` if identical content was already published.

### Poll status

```bash
curl http://localhost:8080/api/v1/documents/FR-2024-CA-000123
```

Status values: `PENDING` → `PROCESSING` → `PUBLISHED` (or `INVALID` / `FAILED`). When `PUBLISHED`, the response includes both artifact contents.

### List all documents

```bash
curl http://localhost:8080/api/v1/documents
```

### Batch upload (multiple files)

```bash
curl -X POST http://localhost:8080/api/v1/documents/batch \
     -F "files=@sample-data/sample1.xml" \
     -F "files=@sample-data/sample2.xml"
```

### Batch from a server-side folder

```bash
curl -X POST http://localhost:8080/api/v1/documents/batch/folder \
     -H "Content-Type: application/json" \
     -d '{"folderPath": "./sample-data"}'
```

### Health and metrics

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus | grep "^documents_"
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_OUTPUT_DIR` | `./output` | Where artifacts are written |
| `APP_CONCURRENCY_POOL_SIZE` | `4` | Worker thread count |
| `APP_CONCURRENCY_QUEUE_CAPACITY` | `100` | Max queued documents |
| `SERVER_PORT` | `8080` | HTTP port |

---

## Project structure

```
src/main/java/com/lexisnexis/transform/
├── LexTransformApplication.java
├── controller/          DocumentController.java
├── config/              AppProperties.java, ExecutorConfig.java
├── domain/
│   ├── exception/       Exception hierarchy + GlobalExceptionHandler
│   ├── model/           DocumentRecord.java
│   ├── resources/       Request/response DTOs
│   └── service/         Orchestrator, Validation, Transformation, Publishing
└── metrics/             DocumentTransformMetrics.java

src/main/resources/
├── schemas/judgment.xsd           XSD validation contract
└── xslt/judgment-to-json.xslt    XSLT 3.0 transformation stylesheet

sample-data/     Two sample XML judgments (French court rulings)
output/          Artifacts written during smoke testing
```

---

## Tests

```bash
mvn test -o
```

10 tests: XSD validation (valid/invalid/bad date), XSLT output correctness, HTTP layer (submit, poll, duplicate, 404, batch).

---

See [SOLUTION.md](SOLUTION.md) for a full explanation of the design.
