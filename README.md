# ai-doc-qna

Upload PDFs, policies, manuals or notes, then ask questions that are answered **only** from what
you uploaded — with citations back to the exact source section.

Built on Spring Boot 4.1, Spring AI 2.0, PostgreSQL + pgvector, and S3-compatible object storage.

## How it answers with citations

Retrieval is done explicitly rather than through `QuestionAnswerAdvisor`. That advisor injects
context into the prompt but does not expose which chunks it used, which makes real citations
impossible. Instead:

1. The question is embedded and matched against the chunk store, keeping scores and metadata.
2. If nothing clears the similarity threshold, the question is refused **without a chat call** —
   correct behaviour and one less request to pay for.
3. Surviving chunks are numbered `[1]…[n]` in the prompt.
4. The model replies as structured output: `{answer, answerable, usedSources}`.
5. `usedSources` is mapped back to the chunks it names, producing citations with filename, page
   number, similarity score and a verbatim snippet. Numbers the model invents are dropped.

`answerable: false` is a first-class outcome, so an out-of-corpus question gets an explicit
"not covered by your documents" instead of a confident fabrication.

## Prerequisites

- **JDK 26** — installed here via Homebrew but keg-only, so it is not on `PATH`:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
  ```
- **Docker** — for Postgres/pgvector, MinIO, and the Testcontainers-backed tests.
- **An OpenAI API key** — only to run the app. The test suite does not need one.

## Running it

```bash
docker compose up -d          # Postgres on 5434, MinIO on 9000 (console 9001)
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
export OPENAI_API_KEY=sk-...
./mvnw spring-boot:run
```

Postgres uses **5434** because 5433 is already taken on this machine. Flyway creates both the
`source_documents` table and the pgvector `vector_store` table on startup, and the MinIO bucket is
created automatically on first run.

## API

Every response uses the envelope `{success, data, error, timestamp}`.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/documents` | multipart `file`; returns 201 with status `PENDING` |
| `GET` | `/api/v1/documents` | paginated (`?page=&size=&sort=`), page size capped at 100 |
| `GET` | `/api/v1/documents/{id}` | ingestion status, chunk count, failure reason |
| `DELETE` | `/api/v1/documents/{id}` | 204; removes chunks and the stored object too |
| `POST` | `/api/v1/questions` | `{"question": "..."}` → answer + citations |

Uploads accept PDF, TXT and MD up to 50 MB. Re-uploading identical content returns 409 rather than
duplicating chunks and skewing retrieval.

### Example

```bash
# Upload — returns immediately; embedding runs in the background
curl -F file=@handbook.pdf localhost:8080/api/v1/documents

# Poll until status is COMPLETED and chunkCount > 0
curl localhost:8080/api/v1/documents/<id>

# Ask
curl -X POST localhost:8080/api/v1/questions \
  -H 'Content-Type: application/json' \
  -d '{"question":"How many days per week can I work remotely?"}'
```

```json
{
  "success": true,
  "data": {
    "question": "How many days per week can I work remotely?",
    "answer": "Full-time employees may work remotely up to three days per week.",
    "answerable": true,
    "retrievedChunks": 4,
    "citations": [
      {
        "reference": 1,
        "documentId": "9f3c…",
        "filename": "handbook.pdf",
        "pageNumber": 1,
        "chunkIndex": 0,
        "snippet": "Section 1 Eligibility. Full time employees may work remotely up to three days each week.",
        "score": 0.87
      }
    ]
  },
  "timestamp": "2026-08-15T09:12:44Z"
}
```

An out-of-corpus question returns `answerable: false` with empty `citations`. The refusal wording
distinguishes three cases that look identical from the outside: nothing uploaded yet, documents
still being embedded, and a corpus that genuinely does not cover the question.

## Ingestion

Upload responds immediately; parsing and embedding run on a bounded background pool, so a
100-page PDF never holds an HTTP request open.

```
POST /documents → validate → SHA-256 (dedupe) → store object → row as PENDING → 201
                                    ↓ after commit, on a worker thread
              PROCESSING → parse → split → embed → vector store → COMPLETED (or FAILED + reason)
```

The listener fires on `AFTER_COMMIT` — on any earlier phase the worker could start before the row
is visible and find nothing to ingest. PDFs go through `PagePdfDocumentReader` so page numbers
survive into citations. A scanned, image-only PDF yields no text and is marked `FAILED` with an
OCR hint rather than silently "completing" as a document that answers nothing.

## Testing

```bash
./mvnw test
```

39 tests: unit tests for the citation-mapping and upload logic, `@WebMvcTest` slices for both
controllers, and Testcontainers integration tests running against real Postgres/pgvector and real
MinIO with the AI models stubbed — so the whole suite runs **without an API key and at no cost**.
The PDF suite builds a real multi-page PDF with PDFBox and asserts page numbers reach the citations.

Four further tests exercise real OpenAI calls and are skipped unless `OPENAI_API_KEY` is set:

```bash
OPENAI_API_KEY=sk-... ./mvnw test -Dtest=RealOpenAiEndToEndTest
```

Those are the ones that prove retrieval really discriminates and that the model genuinely refuses
out-of-corpus questions.

## Configuration

Tunables live under `app.*` in `application.yaml`:

| Property | Default | Purpose |
|---|---|---|
| `app.ingestion.chunk-size-tokens` | 800 | Target chunk size |
| `app.ingestion.max-file-size-bytes` | 50 MB | Upload cap |
| `app.qa.top-k` | 6 | Chunks retrieved per question |
| `app.qa.similarity-threshold` | 0.45 | Below this, the question is refused outright |
| `app.qa.max-snippet-chars` | 320 | Citation snippet length |
| `app.storage.*` | MinIO | Endpoint, bucket, credentials, path-style access |

Raising `similarity-threshold` makes the system refuse more and hallucinate less; lowering it does
the reverse.

### Deploying against real AWS S3

Point `app.storage.endpoint` at S3, set `app.storage.path-style-access: false`, and supply real
credentials. No code changes — the storage adapter is the same.

## Notable constraints

- Embedding dimension is pinned to **1536** (`text-embedding-3-small`) in the Flyway schema.
  Changing the embedding model requires a migration and a full re-embed of every document.
- Each question is answered independently; there is no conversation history and no stored Q&A log.
- No authentication in v1 — every document is visible to every caller.
- Office formats (docx, pptx) would need Tika and are not supported.
- **Lombok is deliberately absent**: 1.18.46 silently generates nothing on JDK 26, so `@Getter`
  and `@Slf4j` would compile away into missing symbols.
