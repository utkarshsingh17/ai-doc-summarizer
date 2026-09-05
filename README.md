# ai-doc-qna

Upload PDFs, policies, manuals or notes, then ask questions that are answered **only** from what
you uploaded — with citations back to the exact source section. Every account only ever sees,
uploads to, or asks questions about its own documents.

Built on Spring Boot 4.1 and Spring AI 2.0. Qdrant is the only datastore — accounts, document
metadata, the originally uploaded files, and chunk embeddings all live there.

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
- **Docker** — for Qdrant and the Testcontainers-backed tests.
- **An OpenAI API key** — only to run the app. The test suite does not need one.

## Running it

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export OPENAI_API_KEY=sk-...
./mvnw spring-boot:run
```

Qdrant starts automatically — `spring-boot-docker-compose` brings `compose.yaml` up before the app
context refreshes and tears it down on shutdown. (`docker compose up -d` still works if you want it
running independently of the app.) The app creates its three collections on startup: `vector_store`
(chunk embeddings, via Spring AI's `initialize-schema: true`) and `users`/`documents` (accounts and
document metadata + raw file bytes, via `QdrantCollectionsInitializer`). Qdrant's dashboard is at
`http://localhost:6333/dashboard` if you want to poke at collections directly.

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
| `DELETE` | `/api/v1/documents/{id}` | 204; removes the document's chunks and its stored bytes |

Uploads accept PDF, TXT and MD up to 50 MB. Re-uploading identical content **for the same
account** returns 409 rather than duplicating chunks — the same file uploaded by two different
accounts is not a conflict. This check is a plain query-then-write, not a database constraint (there
is no database), so it has a narrow race window under truly concurrent identical uploads — see
[Notable constraints](#notable-constraints).

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

Three variants: the whole corpus, a hand-picked subset of documents, or one specific document.

| Method | Path | Body | Notes |
|---|---|---|---|
| `POST` | `/api/v1/questions` | `{"question": "...", "documentIds": [...]}` | `documentIds` omitted or empty answers from every completed document the caller owns; a non-empty list scopes the answer to just those |
| `POST` | `/api/v1/documents/{id}/questions` | `{"question": "..."}` | Answers from that one document only |

```bash
curl -b cookies.txt -X POST localhost:8080/api/v1/questions \
  -H 'Content-Type: application/json' \
  -d '{"question":"How many days per week can I work remotely?"}'
```

```bash
# Scoped to a subset — every id must belong to the caller, same 404-not-403 rule as any other
# document endpoint.
curl -b cookies.txt -X POST localhost:8080/api/v1/questions \
  -H 'Content-Type: application/json' \
  -d '{"question":"How many days per week can I work remotely?","documentIds":["9f3c1a2e-..."]}'
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
`answerable: false` with empty `citations`. When `documentIds` is empty, the refusal wording
distinguishes three cases that look identical from the outside: nothing uploaded yet, documents
still being embedded, and a corpus that genuinely does not cover the question; scoped to a
specific `documentIds` subset (or `POST /documents/{id}/questions`), it's simply "not covered" —
per-document status doesn't apply to a hand-picked set the same way. Either endpoint returns 404
for a document you don't own, same as any other document endpoint.

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
| `STORAGE_UNAVAILABLE` | 503 | Qdrant unreachable (holds accounts, document metadata/bytes, and chunks alike) |
| `AI_UNAVAILABLE` | 503 | Model provider unreachable after retries |
| `AI_REQUEST_FAILED` | 502 | Model provider rejected the request (e.g. bad key) |
| `INTERNAL_ERROR` | 500 | Unhandled exception |

## Ingestion

Upload responds immediately; parsing and embedding run on a bounded background pool, so a
100-page PDF never holds an HTTP request open.

```
POST /documents → validate → SHA-256 (dedupe per account) → upsert point (metadata + bytes,
                                                              status PENDING) → 201
                                    ↓ event fires immediately, on a worker thread
              PROCESSING → parse → split → embed → vector store → COMPLETED (or FAILED + reason)
```

Metadata and the raw uploaded bytes are written together as one Qdrant point, so there is no
separate object store to keep in sync and no "row exists but the file didn't get written" failure
mode. Parsing goes through Apache Tika (`TikaDocumentReader`), which handles PDF/TXT/MD uniformly
and could parse far more formats than that if `app.ingestion.allowed-content-types` allowed them
through. The trade-off: Tika returns one `Document` per file with no per-page split, so **citations
no longer carry a page number** for any file type — `pageNumber` in every citation is `null`. A
scanned, image-only PDF (or any file with no extractable text) yields no text and is marked
`FAILED` with an OCR hint rather than silently "completing" as a document that answers nothing.

## Testing

```bash
./mvnw test
```

47 tests: unit tests for the citation-mapping, upload, and auth logic, `@WebMvcTest` slices for
every controller, and Testcontainers integration tests running against a real Qdrant with the AI
models stubbed — so the whole suite runs **without an API key and at no cost**.

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
| `spring.ai.vectorstore.qdrant.*` | `localhost:6334`, collection `vector_store` | Host, gRPC port, TLS, collection name, schema init |
| `app.jwt.secret` | dev-only fallback | Base64 HMAC-SHA256 key, ≥256 bits. **Set `JWT_SECRET` for anything beyond local dev.** |
| `app.jwt.access-token-expiry-ms` | 900000 (15 min) | `access_token` cookie lifetime |
| `app.jwt.refresh-token-expiry-ms` | 604800000 (7 days) | `refresh_token` cookie lifetime |
| `app.jwt.cookie-secure` | `false` | Cookie `Secure` attribute. **Set `COOKIE_SECURE=true` for anything reachable over the network** — `false` only works because local dev is plain `http://` |

Raising `similarity-threshold` makes the system refuse more and hallucinate less; lowering it does
the reverse.

## Deployment

Backend on [Render](https://render.com), frontend on [Vercel](https://vercel.com), Qdrant on
[Qdrant Cloud](https://cloud.qdrant.io). The frontend proxies `/api/*` to Render through a Vercel
rewrite (`ai-doc-qna-frontend/vercel.json`) rather than calling it cross-origin — the auth cookies
are `SameSite=Lax` with no CSRF token, which only works when the browser sees one origin for
everything. Don't switch the cookies to `SameSite=None` to "fix" a cross-origin failure instead;
that removes the app's only CSRF defense.

1. **Qdrant Cloud**: create a free cluster. You'll get a host like
   `xyz-abc.us-east.aws.cloud.qdrant.io` (gRPC port 6334, TLS on) and an API key.
2. **Render**: connect this repo, and it picks up `render.yaml` (a Docker-based web service,
   health check at `/healthz`). Fill in the dashboard-only env vars it prompts for:
   `OPENAI_API_KEY`, `JWT_SECRET` (a real random Base64 string, not the dev fallback),
   `QDRANT_HOST` (from step 1, without the `https://`), `QDRANT_API_KEY`, and
   `CORS_ALLOWED_ORIGINS` (your Vercel domain — belt-and-suspenders since the proxy means the
   browser shouldn't call Render directly, but harmless to set). Note the assigned
   `https://<name>.onrender.com` URL.
3. **Vercel**: import `ai-doc-qna-frontend`. Update `vercel.json`'s rewrite `destination` to the
   actual Render URL from step 2 if it differs from `ai-doc-qna-backend.onrender.com` (Render
   appends a suffix if that name is already taken). Vercel auto-detects the Vite framework preset;
   no build config or `VITE_API_BASE_URL` needed — same-origin is the production default.
4. Render's free tier spins down after 15 minutes idle; the first request after that cold-starts
   slowly (JVM boot + reconnecting to Qdrant Cloud).

## Notable constraints

- Embedding dimension is pinned to **1536** (`text-embedding-3-small`) in the Qdrant collection,
  created at startup from `spring.ai.vectorstore.qdrant.*` rather than a Flyway migration. Changing
  the embedding model still means a new collection (or a wipe) and a full re-embed of every
  document.
- **No database-level uniqueness.** Qdrant has no unique constraints, so a duplicate email or a
  duplicate (owner, checksum) upload is rejected by a plain check-then-write in application code,
  not a database constraint. Two genuinely concurrent requests for the same email or the same
  content can both pass the check before either writes — an accepted trade-off of using Qdrant as
  the only datastore, not a bug.
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
