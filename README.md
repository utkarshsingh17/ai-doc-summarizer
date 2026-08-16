# ai-doc-qna

Upload PDFs, policies, manuals or notes, then ask questions that are answered **only** from what
you uploaded — with citations back to the exact source section. Every account only ever sees,
uploads to, or asks questions about its own documents.

Built on Spring Boot 4.1, Spring AI 2.0, PostgreSQL + pgvector, and S3-compatible object storage.

## How it answers with citations

Retrieval is done explicitly rather than through `QuestionAnswerAdvisor`. That advisor injects
context into the prompt but does not expose which chunks it used, which makes real citations
impossible. Instead:

1. The question is embedded and matched against the caller's own chunks, keeping scores and
   metadata.
2. If nothing clears the similarity threshold, the question is refused **without a chat call** —
   correct behaviour and one less request to pay for.
3. Surviving chunks are numbered `[1]…[n]` in the prompt.
4. The model replies as structured output: `{answer, answerable, usedSources}`.
5. `usedSources` is mapped back to the chunks it names, producing citations with filename,
   similarity score and a verbatim snippet. Numbers the model invents are dropped.

`answerable: false` is a first-class outcome, so an out-of-corpus question gets an explicit
"not covered by your documents" instead of a confident fabrication.

## Prerequisites

- **JDK 25** — installed here via Homebrew but keg-only, so it is not on `PATH`:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
  ```
- **Docker** — for Postgres/pgvector, MinIO, and the Testcontainers-backed tests.
- **An OpenAI API key** — only to run the app. The test suite does not need one.

## Running it

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export OPENAI_API_KEY=sk-...
./mvnw spring-boot:run
```

Postgres and MinIO start automatically — `spring-boot-docker-compose` brings `compose.yaml` up
before the app context refreshes and tears it down on shutdown. (`docker compose up -d` still
works if you want the containers running independently of the app.) Postgres uses **5434** because
5433 is already taken on this machine. Flyway creates the schema — `users`, `source_documents`,
the pgvector `vector_store` table — on startup, and the MinIO bucket is created automatically on
first run.

## API

Every response uses the envelope `{success, data, error, timestamp}`. Every endpoint below except
`/api/v1/auth/**` requires authentication.

### Authentication

Tokens travel as **`HttpOnly` cookies**, never in a response body — nothing to store or attach by
hand. `access_token` is sent on every request (`Path=/`); `refresh_token` is scoped to
`Path=/api/v1/auth/refresh` so it is never sent anywhere else. A browser handles both
automatically; with `curl`, use a cookie jar (`-c cookies.txt -b cookies.txt`).

| Method | Path | Body | Notes |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | `{"email", "password"}` | 201; password 8–100 chars |
| `POST` | `/api/v1/auth/login` | `{"email", "password"}` | 200 |
| `POST` | `/api/v1/auth/refresh` | *(none)* | 200; reads the `refresh_token` cookie |
| `POST` | `/api/v1/auth/logout` | *(none)* | 200; clears both cookies |

```bash
# Register (or /login) — sets cookies, jar picks them up automatically from here on
curl -c cookies.txt -X POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"jane@example.com","password":"correct horse battery"}'
```

```json
{ "success": true, "timestamp": "2026-08-16T09:12:44Z" }
```

`access_token` expires after 15 minutes; `refresh_token` after 7 days. Refresh reissues both:

```bash
curl -c cookies.txt -b cookies.txt -X POST localhost:8080/api/v1/auth/refresh
```

Refresh is stateless — there is no server-side revocation list, so a leaked refresh token stays
valid until it expires on its own.

### Documents

All scoped to the authenticated caller — another account's document behaves as if it does not
exist (404, not 403, so its existence is never revealed).

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/documents` | multipart `file`; returns 201 with status `PENDING` |
| `GET` | `/api/v1/documents` | paginated (`?page=&size=&sort=`), page size capped at 100 |
| `GET` | `/api/v1/documents/{id}` | ingestion status, chunk count, failure reason |
| `DELETE` | `/api/v1/documents/{id}` | 204; removes chunks and the stored object too |

Uploads accept PDF, TXT and MD up to 50 MB. Re-uploading identical content **for the same
account** returns 409 rather than duplicating chunks — the same file uploaded by two different
accounts is not a conflict.

```bash
curl -b cookies.txt -F file=@handbook.pdf localhost:8080/api/v1/documents
```

```json
{
  "success": true,
  "data": {
    "id": "9f3c1a2e-...",
    "filename": "handbook.pdf",
    "contentType": "application/pdf",
    "sizeBytes": 245098,
    "status": "PENDING",
    "chunkCount": 0,
    "errorMessage": null,
    "createdAt": "2026-08-16T09:12:44Z",
    "updatedAt": "2026-08-16T09:12:44Z"
  },
  "timestamp": "2026-08-16T09:12:44Z"
}
```

Poll `GET /api/v1/documents/{id}` until `status` is `COMPLETED` (or `FAILED`, with a reason in
`errorMessage`). `GET /api/v1/documents` wraps the same shape in a page:

```json
{
  "success": true,
  "data": {
    "content": [ /* DocumentResponse objects, as above */ ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "last": true
  },
  "timestamp": "2026-08-16T09:13:01Z"
}
```

### Questions

Two variants: the whole corpus, or one specific document.

| Method | Path | Body | Notes |
|---|---|---|---|
| `POST` | `/api/v1/questions` | `{"question": "..."}` | Answers from every completed document the caller owns |
| `POST` | `/api/v1/documents/{id}/questions` | `{"question": "..."}` | Answers from that one document only |

```bash
curl -b cookies.txt -X POST localhost:8080/api/v1/questions \
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
        "documentId": "9f3c1a2e-...",
        "filename": "handbook.pdf",
        "pageNumber": null,
        "chunkIndex": 0,
        "snippet": "Section 1 Eligibility. Full time employees may work remotely up to three days each week.",
        "score": 0.87
      }
    ]
  },
  "timestamp": "2026-08-15T09:12:44Z"
}
```

`pageNumber` is always `null` — see [Ingestion](#ingestion). An out-of-corpus question returns
`answerable: false` with empty `citations`. The refusal wording distinguishes three cases that
look identical from the outside: nothing uploaded yet, documents still being embedded, and a
corpus that genuinely does not cover the question. `POST /documents/{id}/questions` against a
document you don't own returns 404, same as any other document endpoint.

### Errors

Every non-2xx response uses the same envelope with `error.code`/`error.message`/`error.details`:

```json
{
  "success": false,
  "error": { "code": "DOCUMENT_NOT_FOUND", "message": "Document 9f3c... not found", "details": [] },
  "timestamp": "2026-08-16T09:13:44Z"
}
```

| Code | Status | Cause |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Request body failed `@Valid` (e.g. blank question, short password) |
| `UNAUTHORIZED` | 401 | No/expired/invalid access token, or missing `refresh_token` cookie on refresh |
| `INVALID_CREDENTIALS` | 401 | Wrong email or password on login |
| `INVALID_TOKEN` | 401 | Refresh token invalid or expired |
| `FORBIDDEN` | 403 | Authenticated, but not permitted (not currently reachable — ownership checks 404 instead) |
| `DOCUMENT_NOT_FOUND` | 404 | Unknown id, or a document owned by someone else |
| `DUPLICATE_DOCUMENT` | 409 | Identical content already uploaded by this account |
| `EMAIL_ALREADY_REGISTERED` | 409 | Email already has an account |
| `UNSUPPORTED_MEDIA_TYPE` / `UNSUPPORTED_FILE_TYPE` | 415 | Wrong `Content-Type`, or a file type outside `app.ingestion.allowed-content-types` |
| `FILE_TOO_LARGE` | 413 | Over `app.ingestion.max-file-size-bytes` |
| `STORAGE_UNAVAILABLE` | 503 | Object storage (MinIO/S3) unreachable |
| `AI_UNAVAILABLE` | 503 | Model provider unreachable after retries |
| `AI_REQUEST_FAILED` | 502 | Model provider rejected the request (e.g. bad key) |
| `INTERNAL_ERROR` | 500 | Unhandled exception |

## Ingestion

Upload responds immediately; parsing and embedding run on a bounded background pool, so a
100-page PDF never holds an HTTP request open.

```
POST /documents → validate → SHA-256 (dedupe per account) → store object → row as PENDING → 201
                                    ↓ after commit, on a worker thread
              PROCESSING → parse → split → embed → vector store → COMPLETED (or FAILED + reason)
```

The listener fires on `AFTER_COMMIT` — on any earlier phase the worker could start before the row
is visible and find nothing to ingest. Parsing goes through Apache Tika (`TikaDocumentReader`),
which handles PDF/TXT/MD uniformly and could parse far more formats than that if
`app.ingestion.allowed-content-types` allowed them through. The trade-off: Tika returns one
`Document` per file with no per-page split, so **citations no longer carry a page number** for any
file type — `pageNumber` in every citation is `null`. A scanned, image-only PDF (or any file with
no extractable text) yields no text and is marked `FAILED` with an OCR hint rather than silently
"completing" as a document that answers nothing.

## Testing

```bash
./mvnw test
```

48 tests: unit tests for the citation-mapping, upload, and auth logic, `@WebMvcTest` slices for
every controller, and Testcontainers integration tests running against real Postgres/pgvector and
real MinIO with the AI models stubbed — so the whole suite runs **without an API key and at no
cost**.

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
| `app.jwt.secret` | dev-only fallback | Base64 HMAC-SHA256 key, ≥256 bits. **Set `JWT_SECRET` for anything beyond local dev.** |
| `app.jwt.access-token-expiry-ms` | 900000 (15 min) | `access_token` cookie lifetime |
| `app.jwt.refresh-token-expiry-ms` | 604800000 (7 days) | `refresh_token` cookie lifetime |
| `app.jwt.cookie-secure` | `false` | Cookie `Secure` attribute. **Set `COOKIE_SECURE=true` for anything reachable over the network** — `false` only works because local dev is plain `http://` |

Raising `similarity-threshold` makes the system refuse more and hallucinate less; lowering it does
the reverse.

### Deploying against real AWS S3

Point `app.storage.endpoint` at S3, set `app.storage.path-style-access: false`, and supply real
credentials. No code changes — the storage adapter is the same.

## Notable constraints

- Embedding dimension is pinned to **1536** (`text-embedding-3-small`) in the Flyway schema.
  Changing the embedding model requires a migration and a full re-embed of every document.
- Each question is answered independently; there is no conversation history and no stored Q&A log.
- Refresh tokens are stateless — there is no server-side revocation, so a leaked refresh token
  works until it naturally expires.
- CSRF defense is `SameSite=Lax` on the auth cookies; there is no separate CSRF token flow.
- PDF citations carry no page number — see [Ingestion](#ingestion).
- **Lombok is deliberately absent**, evaluated and rejected on both JDK 25 and JDK 26: the newest
  release (1.18.46) silently generates nothing — no error, just missing symbols — the instant
  `javac` sees a version-targeting flag (`--release`, or plain `-source`/`-target`), which Maven's
  compiler plugin always passes. It only "works" invoked with no version flags at all, which is
  not how any real build compiles this project.
