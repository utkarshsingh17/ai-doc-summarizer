# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A document question-answering service: upload PDFs/policies/manuals/notes, they get split and
embedded, and questions are answered **only** from the uploaded corpus with citations back to the
source section. Spring Boot 4.1, Spring AI 2.0, PostgreSQL + pgvector, S3-compatible storage.
See `README.md` for the API and user-facing behaviour.

## Toolchain

`pom.xml` targets **Java 26**. The JDK is installed via Homebrew but is keg-only, so `java` on
`PATH` is 17 and every Maven command needs:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
```

Maven Wrapper is `distributionType=only-script` — `mvnw` downloads Maven 3.9.16 on first run.

## Commands

```bash
docker compose up -d                                     # Postgres 5434, MinIO 9000/9001
./mvnw test                                              # full suite; no API key, no cost
./mvnw test -Dtest=QaServiceTest                         # one class
./mvnw test -Dtest=QaServiceTest#ask_whenNoChunksClearThreshold_shouldRefuseWithoutCallingTheModel
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run             # run the app
OPENAI_API_KEY=sk-... ./mvnw test -Dtest=RealOpenAiEndToEndTest   # real provider calls
```

No linter or formatter is configured.

## Architecture

Layered, grouped by feature. Controllers do HTTP only and call one service; services own
`@Transactional`; entities never cross the HTTP boundary.

```
config/     AppProperties (@ConfigurationProperties "app"), AiConfig, StorageConfig, AsyncConfig
common/     ApiResponse/ApiError envelope, GlobalExceptionHandler, exception/
storage/    DocumentStorage port + S3DocumentStorage adapter (MinIO and real S3 alike)
document/   upload, ingestion pipeline, SourceDocument entity, controller
qa/         retrieval, grounded answer generation, citation mapping, controller
```

### Things that will bite you

**The entity is `SourceDocument`, not `Document`.** `org.springframework.ai.document.Document` is
the chunk type and appears in the same classes constantly.

**Chunks have no JPA entity.** They live in the pgvector `vector_store` table that Spring AI owns.
Ownership is tracked purely through chunk metadata (`ChunkMetadata`: `document_id`, `filename`,
`page_number`, `chunk_index`), and deletion uses a metadata filter on `document_id`. If you add a
field that citations need, it goes in chunk metadata — there is no join available.

**Flyway owns the vector schema**, not Spring AI
(`spring.ai.vectorstore.pgvector.initialize-schema: false`). `V3__create_vector_store.sql` must
keep the column names `id/content/metadata/embedding` — `PgVectorSchemaValidator` checks them at
startup — and `vector(1536)` must match the embedding model. `metadata` is `jsonb` because
PgVectorStore binds `?::jsonb` and filters with `metadata::jsonb @@ …`.

**`ddl-auto: validate` is deliberate.** Entity/migration mismatches fail startup. Note `CHAR(n)`
reports as `bpchar` and fails validation against a `String` field — use `VARCHAR`.

**Ingestion runs `@Async` on `@TransactionalEventListener(AFTER_COMMIT)`.** Any earlier phase and
the worker can start before the row is visible. Status writes go through `IngestionStatusWriter`,
a separate bean, because a `@Transactional` method called from within the same class bypasses the
proxy and the `REQUIRES_NEW` transaction would never start.

**Chunk ids are derived from `documentId + index`.** Spring AI's default id is a content hash, so
two chunks with identical text (repeated headers) would collide and the store's
`ON CONFLICT DO UPDATE` would silently merge them.

## Why retrieval is explicit

`QuestionAnswerAdvisor` is deliberately **not** used. It injects context but hides which chunks it
used, which makes citations impossible. `RetrievalService` searches, `QaService` numbers the
excerpts `[1]…[n]`, the model returns `GroundedAnswer{answer, answerable, usedSources}` via
`.responseEntity(...)`, and cited numbers are mapped back to real chunks. Out-of-range numbers are
dropped — a hallucinated citation is worse than a missing one.

When nothing clears `app.qa.similarity-threshold`, the question is refused **without a chat call**.
Keep that short-circuit; it is both correct behaviour and the cost control. The refusal wording then
consults `DocumentService.corpusStatus()` to separate "nothing uploaded", "still ingesting" and
"genuinely not covered" — telling a user their content is absent while it is mid-embed is wrong.
That count runs only on the no-match path, so the populated-corpus path stays a single query.

## Version traps in this stack

These are all things that look right and are not, in Boot 4 / Spring AI 2.0:

- **No Lombok.** 1.18.46 silently generates *nothing* on JDK 26 — no error, just missing symbols.
  Use records, explicit constructors, and `LoggerFactory`. Do not reintroduce it.
- `spring-boot-starter-webmvc`, not `-web`. Tests use `spring-boot-starter-webmvc-test` and
  `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`.
- Flyway is **not** transitive from the JPA starter; `spring-boot-starter-flyway` plus
  `flyway-database-postgresql` are both declared explicitly.
- Boot 4.1's parent does not manage Testcontainers versions — `testcontainers-bom` is imported.
- `@MockitoBean`, not `@MockBean`.
- Jackson 3 (`tools.jackson.databind`), but annotations are still `com.fasterxml.jackson.annotation`.
- `HttpStatus.CONTENT_TOO_LARGE`, not the deprecated `PAYLOAD_TOO_LARGE`.
- Spring AI properties are flattened: `spring.ai.openai.chat.model`, no `.options` segment.
- `TransientAiException`/`NonTransientAiException` live in the **optional** `spring-ai-retry`
  artifact, which is not on the classpath. Provider errors surface as `com.openai.errors.*` and are
  classified by `AiServiceException.translate(...)`.
- **Do not add `@Retryable` around model calls.** The `openai-java` client already retries
  internally (`maxRetries` defaults to 2); a second layer compounds to ~9 HTTP calls per question.

## Testing

Unit → slice → integration, per the testing-pyramid skill. Integration tests use Testcontainers
(pgvector + MinIO) with `StubAiConfiguration` replacing the chat and embedding models, so the whole
suite runs with no API key and no cost. The stub embedding returns a constant vector so cosine
distance is always 0 — deterministic, and keeps the tests about plumbing rather than embedding
quality. `RealOpenAiEndToEndTest` is guarded by `@EnabledIfEnvironmentVariable`.

**Docker Engine 29 vs Testcontainers**: the bundled docker-java negotiates API 1.32, which Docker
29 rejects (`MinAPIVersion` 1.40). Surefire pins `-Dapi.version=1.44`. If Testcontainers reports
"Could not find a valid Docker environment", that pin is why — do not remove it.

Test naming is `method_condition_expectedBehavior`. Assertions use AssertJ.

## Conventions

- Base package `com.utkarsh.ai_doc_qna` (underscored; `ai-doc-qna` is not a legal package name).
- All responses wrapped in `ApiResponse`; errors mapped centrally in `GlobalExceptionHandler`.
- Paths are literal `/api/v1/...`. There is only one version, so Boot 4's native version routing is
  not configured yet — wire it when a v2 actually exists rather than duplicating controllers.
- Config in `application.yaml`; anything tunable goes under `app.*` and is bound to `AppProperties`.
- Source files use tabs only in `AiDocQnaApplication.java` (Initializr output); everything else
  uses 4 spaces.
