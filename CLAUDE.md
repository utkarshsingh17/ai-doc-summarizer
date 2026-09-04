# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A document question-answering service: upload PDFs/policies/manuals/notes, they get split and
embedded, and questions are answered **only** from the uploaded corpus with citations back to the
source section. Documents are private per account — every upload, list, delete, and question is
scoped to the authenticated user. Spring Boot 4.1, Spring AI 2.0. **Qdrant is the only datastore**
— accounts, document metadata, the originally uploaded file bytes, and chunk embeddings all live
there; there is no relational database and no separate object store. See `README.md` for the API
and user-facing behaviour.

## Toolchain

`pom.xml` targets **Java 25**. The JDK is installed via Homebrew but is keg-only, so `java` on
`PATH` is whatever else is installed and every Maven command needs:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

Maven Wrapper is `distributionType=only-script` — `mvnw` downloads Maven 3.9.16 on first run.

## Commands

```bash
docker compose up -d                                     # Qdrant 6333 (REST/dashboard), 6334 (gRPC)
./mvnw test                                              # full suite; no API key, no cost
./mvnw test -Dtest=QaServiceTest                         # one class
./mvnw test -Dtest=QaServiceTest#ask_whenNoChunksClearThreshold_shouldRefuseWithoutCallingTheModel
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run             # run the app
OPENAI_API_KEY=sk-... ./mvnw test -Dtest=RealOpenAiEndToEndTest   # real provider calls
```

No linter or formatter is configured.

## Architecture

Layered, grouped by feature. Controllers do HTTP only and call one service; domain objects never
cross the HTTP boundary. There is no transaction manager anywhere in this app — Qdrant has no
transactions, so every repository write is a single point upsert/update and is atomic only at that
granularity (see the dedupe/uniqueness note below).

```
config/     AppProperties (@ConfigurationProperties "app"), AiConfig, QdrantCollectionsInitializer,
            AsyncConfig, SecurityConfig
common/     ApiResponse/ApiError envelope, GlobalExceptionHandler, exception/, QdrantSupport
auth/       User record, UserRepository port + QdrantUserRepository, JWT issuing/validation,
            UserPrincipal, register/login/refresh/logout
document/   upload, ingestion pipeline, SourceDocument, SourceDocumentRepository port +
            QdrantSourceDocumentRepository, controller
qa/         retrieval, grounded answer generation, citation mapping, controller
```

### Things that will bite you

**The entity is `SourceDocument`, not `Document`.** `org.springframework.ai.document.Document` is
the chunk type and appears in the same classes constantly.

**Three Qdrant collections, three very different shapes.** `vector_store` holds chunk embeddings
and is entirely Spring AI's — `QdrantVectorStoreAutoConfiguration` creates it at startup
(`spring.ai.vectorstore.qdrant.initialize-schema: true`, dimension 1536, cosine distance) and the
app only ever touches it through the injected `VectorStore` bean. `users` and `documents` are this
app's own — `QdrantCollectionsInitializer` creates them (a 1-dimensional dummy vector each, since
Qdrant requires every point to have one but nothing in either collection is ever searched by
similarity, only filtered and scrolled), and `QdrantUserRepository`/`QdrantSourceDocumentRepository`
read and write them directly via the Qdrant Java client. Ownership is tracked purely through chunk
metadata (`ChunkMetadata`: `document_id`, `owner_id`, `filename`, `page_number`, `chunk_index`),
and chunk deletion uses a metadata filter on `document_id`. If you add a field that citations need,
it goes in chunk metadata — there is no join available.

**A document's metadata and its raw uploaded bytes are the same Qdrant point.** The `content`
payload field (base64) replaces what used to be a separate S3 object addressed by a storage key —
see `QdrantSourceDocumentRepository`. That means deleting a document is one point delete, not a
row-plus-object cleanup, but it also means `content` must be excluded from every read except the
one that actually needs the bytes (`loadContent`), or listing/fetching a document's status pulls
its (up to ~67 MB base64-encoded) file into memory for no reason. It also means an ingestion status
transition (`IngestionStatusWriter` → `SourceDocumentRepository.updateStatus`) **must** use
Qdrant's partial `setPayload`, never a full point `upsert` — an upsert replaces a point's entire
payload, which would silently wipe the stored `content` on every `PENDING → PROCESSING →
COMPLETED` transition. `save` (the creation path, in `DocumentService.upload`) is the only place an
upsert with the full payload — including `content` — is correct.

**There is no database uniqueness left, anywhere.** The old Postgres schema enforced a unique
`email` and a unique `(owner_id, checksum)` per user at the DB level; Qdrant has no equivalent, so
`QdrantUserRepository.save`/`DocumentService.upload`'s dedupe check are both a plain
check-then-write with a real (if narrow) race window — two concurrent registrations for the same
email, or two concurrent uploads of identical content, can both pass the check before either
writes. This was an accepted trade-off of moving everything onto Qdrant, not an oversight; don't
try to paper over it with a `@Transactional`-style fix, since there is nothing transactional to
attach it to.

**Qdrant's Docker image has no shell utility in it at all — no curl, wget, or nc.** That's why
`compose.yaml`'s `qdrant` service has no `healthcheck:` — a shell-based probe simply cannot run
inside that container, and nothing else in the file `depends_on` it anyway.
`TestcontainersConfiguration`'s `QdrantContainer` doesn't need one either; its own wait strategy
polls `/readyz` from outside the container. Also note the Maven artifact is
`org.testcontainers:qdrant`, not `testcontainers-qdrant` — this project's `testcontainers-bom`
names its module after the technology alone. Getting a `QdrantContainer` bean picked up by
`@ServiceConnection` also requires `spring-ai-spring-boot-testcontainers` on the test classpath —
that wiring is not native to `spring-boot-testcontainers` the way it is for a JDBC datasource,
because Qdrant is a Spring AI vector store, not a Spring Boot-native connection type.

**`@EnableSpringDataWebSupport` on `AiDocQnaApplication` is required, not decorative.** With no
Spring Data repository starter on the classpath (Qdrant isn't a Spring Data module, and
`spring-data-commons` is declared directly just for `Page`/`Pageable`/`Sort`/`PageImpl`),
`SpringDataWebAutoConfiguration` never activates on its own. Remove the annotation and
`@PageableDefault Pageable pageable` on `DocumentController.list` silently stops being resolved
from `?page=&size=&sort=` — Spring MVC falls back to treating `Pageable` as a request-bound bean
via its (nonexistent) constructor and every call to the endpoint 500s.

**A `@Bean` method cannot share its name with its own `@Configuration` class.** Spring registers a
`@Configuration` class itself as a bean under a name derived from the class
(`qdrantCollectionsInitializer` for `QdrantCollectionsInitializer`); a `@Bean` method in that same
class with the same generated name collides with it — `BeanDefinitionOverrideException` at context
startup, only on profiles/tests that actually load that config. This is why the `ApplicationRunner`
bean method in `QdrantCollectionsInitializer` is named `qdrantCollectionsRunner`, not
`qdrantCollectionsInitializer`.

**Pagination is scrolled and sorted in memory, not pushed down to Qdrant.** Qdrant's scroll API is
built for cursor-based streaming, not page-number pagination, so
`QdrantSourceDocumentRepository.findAllByOwnerId` fetches everything matching the owner in one
scroll (capped at `MAX_DOCUMENTS_PER_OWNER`), sorts and slices in Java, and wraps the result in a
`PageImpl`. Fine at this app's scale; would need rethinking for an owner with a huge corpus.

**Ingestion runs `@Async` on a plain `@EventListener`, not `@TransactionalEventListener`.** There
is nothing transactional left to wait on — `DocumentService.upload`'s Qdrant upsert is synchronous
and immediately visible, unlike the old JPA row that only became visible to other connections after
commit. Status writes still go through `IngestionStatusWriter`, a separate bean, purely so the
mutate-then-persist shape reads the same at every call site — there is no transactional proxy to
bypass here the way there was under JPA.

**Chunk ids are derived from `documentId + index`.** Spring AI's default id is a content hash, so
two chunks with identical text (repeated headers) would collide and the store's upsert-by-id
behavior would silently merge them.

**Parsing goes through `TikaDocumentReader`, not `PagePdfDocumentReader`.** One `Document` per
file, no `page_number` metadata, for every content type Tika handles (pdf/txt/md and beyond).
Citations name the file but never a page. This also means the splitter's tokenizer runs over a
whole file's text in one string rather than one page at a time — keep that in mind before blaming
a large-document failure on something else.

**`org.apache.pdfbox:pdfbox` (the actual PDF text-extraction engine) must be a compile-scope
dependency in `pom.xml`, not just present transitively.** `tika-parser-pdf-module`'s own POM pulls
in `pdfbox-tools`, whose POM lists `pdfbox-debugger`/`commons-io`/`picocli` — not `pdfbox` core
itself. Without it, every PDF silently extracts to an empty `Document` — no exception, no log
line, just an empty-chunks "No extractable text found" failure indistinguishable from a genuinely
scanned file. `./mvnw test` will not catch a regression here: Testcontainers/`PdfCitationIntegrationTest`
builds and parses a real PDF, but if `pdfbox` is ever declared at `test` scope instead of
compile/default scope, the test classpath has it and passes while `spring-boot:run`'s runtime
classpath does not — this exact thing happened once already. Verify with
`mvn dependency:tree -Dincludes=org.apache.pdfbox` and confirm `pdfbox:jar:...:compile`, not
`:test`, whenever touching Tika or PDFBox dependencies.

**Auth tokens live in `HttpOnly` cookies, not `Authorization` headers.** `access_token`
(`Path=/`) and `refresh_token` (`Path=/api/v1/auth/refresh`), both `SameSite=Lax`. Nothing comes
back in the JSON body from `/auth/register`, `/auth/login`, or `/auth/refresh` — an XSS bug or an
access log can't leak what was never JS-readable or logged as JSON. `curl` needs a cookie jar
(`-c`/`-b`), not a Bearer header; `refresh` takes no request body, the cookie *is* the request.
CSRF defense is `SameSite=Lax` alone — CSRF token machinery is deliberately not there.

**CORS must list exact origins, never a wildcard or `allowedOriginPatterns`.** Cookie-based auth
means every cross-origin request is credentialed, and browsers reject a credentialed request
outright if the response reflects `*` for `Access-Control-Allow-Origin`. `SecurityConfig.
corsConfigurationSource()` builds the list from `app.cors.allowed-origins`
(`CORS_ALLOWED_ORIGINS` env var, defaults to Vite's `http://localhost:5173`). `OPTIONS` preflight
is `permitAll()` ahead of the auth rule — the browser sends no cookie on preflight, so requiring
auth on it would stop the real request from ever going out.

**Chunk metadata carries `owner_id` alongside `document_id`.** Both whole-corpus retrieval
(`RetrievalService.retrieve`) and per-document retrieval (`retrieveForDocument`) filter on these,
so one user's question is never answered from another user's uploads. If you add a new retrieval
path, filter by owner (or verify document ownership first) or it will cross-leak content between
accounts.

## Why retrieval is explicit

`QuestionAnswerAdvisor` is deliberately **not** used. It injects context but hides which chunks it
used, which makes citations impossible. `RetrievalService` searches, `QaService` numbers the
excerpts `[1]…[n]`, the model returns `GroundedAnswer{answer, answerable, usedSources}` via
`.responseEntity(...)`, and cited numbers are mapped back to real chunks. Out-of-range numbers are
dropped — a hallucinated citation is worse than a missing one.

When nothing clears `app.qa.similarity-threshold`, the question is refused **without a chat call**.
Keep that short-circuit; it is both correct behaviour and the cost control. The refusal wording then
consults `DocumentService.corpusStatus(ownerId)` to separate "nothing uploaded", "still ingesting"
and "genuinely not covered" — telling a user their content is absent while it is mid-embed is wrong.
That count runs only on the no-match path, so the populated-corpus path stays a single query. It is
scoped to the caller, same as retrieval itself — another user's in-progress uploads must not change
what this user is told.

`QaService.askAboutDocument` follows the same shape but skips the corpus-status branching: a
document scoped to one id is either owned by the caller (verified via `DocumentService.get`, which
throws `DocumentNotFoundException` for someone else's document — existence is never leaked) or the
question never reaches retrieval at all.

## Version traps in this stack

These are all things that look right and are not, in Boot 4 / Spring AI 2.0:

- **No Lombok.** 1.18.46 (the newest release) silently generates *nothing* — no error, just
  missing symbols — the instant `javac` sees a version-targeting flag (`--release`, or even plain
  `-source`/`-target` set to match the running JDK exactly). Maven's compiler plugin always passes
  one of these (Boot's parent sets `maven.compiler.release`), so this isn't avoidable from a
  Maven build, on JDK 25 or JDK 26 alike. `javac` with no version flags at all works fine, which
  is what makes this trap easy to "verify" wrongly — that invocation never happens in a real
  build. Re-verify empirically with the exact flags Maven passes (not from the changelog, not
  from a bare `javac` smoke test) before ever reintroducing it. Use records, explicit
  constructors, and `LoggerFactory`.
- `spring-boot-starter-webmvc`, not `-web`. Tests use `spring-boot-starter-webmvc-test` and
  `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`.
- `org.springframework.data:spring-data-commons` is declared directly (not via any Spring Data
  starter — there isn't one, Qdrant isn't a Spring Data module) purely for `Page`/`Pageable`/
  `Sort`/`PageImpl`, and needs `@EnableSpringDataWebSupport` spelled out explicitly alongside it —
  see "Things that will bite you" above.
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

Unit → slice → integration, per the testing-pyramid skill. Integration tests use a single
Testcontainers `QdrantContainer` — one container backs users, document metadata and chunk
embeddings alike — with `StubAiConfiguration` replacing the chat and embedding models, so the whole
suite runs with no API key and no cost. The stub embedding returns a constant vector so cosine
distance is always 0 — deterministic, and keeps the tests about plumbing rather than embedding
quality. `RealOpenAiEndToEndTest` is guarded by `@EnabledIfEnvironmentVariable`.
`DocumentQaIntegrationTest`/`PdfCitationIntegrationTest`/`RealOpenAiEndToEndTest` don't wipe the
chunk collection between tests — every upload gets a fresh random `document_id`, so nothing from an
earlier test is reachable by a later one's filtered searches, the same isolation the app relies on
in production.

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
