# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A document question-answering service: upload PDFs/policies/manuals/notes, they get split and
embedded, and questions are answered **only** from the uploaded corpus with citations back to the
source section. Documents are private per account — every upload, list, delete, and question is
scoped to the authenticated user. Spring Boot 4.1, Spring AI 2.0. Two datastores, split by shape:
**Postgres holds accounts** (the one thing this app needs real relational guarantees — a unique
email — for); **Qdrant holds everything else** — document metadata, chunk embeddings, and (only
transiently, until ingestion finishes) the originally uploaded file bytes. There is no separate
object store. See `README.md` for the API and user-facing behaviour.

## Toolchain

`pom.xml` targets **Java 25**. The JDK is installed via Homebrew but is keg-only, so `java` on
`PATH` is whatever else is installed and every Maven command needs:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

Maven Wrapper is `distributionType=only-script` — `mvnw` downloads Maven 3.9.16 on first run.

## Commands

```bash
docker compose up -d                                     # Postgres 5434, Qdrant 6333 (REST/dashboard) + 6334 (gRPC)
./mvnw test                                              # full suite; no API key, no cost
./mvnw test -Dtest=QaServiceTest                         # one class
./mvnw test -Dtest=QaServiceTest#ask_whenNoChunksClearThreshold_shouldRefuseWithoutCallingTheModel
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run             # run the app
OPENAI_API_KEY=sk-... ./mvnw test -Dtest=RealOpenAiEndToEndTest   # real provider calls
```

No linter or formatter is configured.

## Deployment

Backend on Render (`Dockerfile` + `render.yaml`), frontend on Vercel, Postgres as a Render-managed
database (provisioned by `render.yaml`'s `databases:` block, wired into the web service via
`fromDatabase`), Qdrant on Qdrant Cloud — see `README.md`'s Deployment section for the full env
var list. Worth knowing if you touch this later:

- **The frontend proxies `/api/*` to Render through a Vercel rewrite (`vercel.json`) instead of
  calling it cross-origin.** Auth cookies are `SameSite=Lax` with no CSRF token (see below) — that
  scheme only works when the browser sees everything as one origin. A cross-origin call from
  `*.vercel.app` straight to `*.onrender.com` would never carry the cookie at all, no matter how
  CORS is configured; `SameSite=Lax` blocks it before CORS is even consulted. Don't "fix" a
  cross-origin auth failure by switching to `SameSite=None` without deliberately deciding to take
  on real CSRF exposure — the proxy is what makes the existing no-CSRF-token design still correct.
- **`server.port` reads `${PORT:8085}`.** Render assigns the listen port via `$PORT`; nothing sets
  that locally, so local dev is unaffected. `GET /healthz` is unauthenticated on purpose — it's
  Render's health check, and a platform prober isn't going to present a JWT cookie.
- **A managed Qdrant instance is not the same as the local Docker one.** Qdrant Cloud (server
  1.19 at least) rejects a filtered `scroll`/`count`/`search` on an un-indexed keyword field
  outright (`INVALID_ARGUMENT: Index required but not found`) instead of falling back to a full
  scan the way local/self-hosted Qdrant does — this is why `QdrantCollectionsInitializer` creates
  payload indexes on every field this app ever filters on, not just the collections themselves.
  If retrieval or a document lookup starts throwing that error after adding a new filtered field,
  it needs an index added there too. `QDRANT_HOST` from the Cloud dashboard's "Cluster URL" also
  comes with an `https://` scheme prefix that must be stripped — the client wants a bare hostname
  and fails with a confusing `URISyntaxException` if you paste the URL as-is.

## Architecture

Layered, grouped by feature. Controllers do HTTP only and call one service; domain objects never
cross the HTTP boundary. `auth` is JPA/Postgres-backed and transactional in the ordinary Spring
way; `document` is Qdrant-backed and is not — Qdrant has no transactions, so every
`SourceDocumentRepository` write is a single point upsert/update, atomic only at that granularity
(see the dedupe/uniqueness note below).

```
config/     AppProperties (@ConfigurationProperties "app"), AiConfig, QdrantCollectionsInitializer,
            AsyncConfig, SecurityConfig
common/     ApiResponse/ApiError envelope, GlobalExceptionHandler, exception/, QdrantSupport
auth/       User entity (JPA/Postgres), UserRepository, JWT issuing/validation, UserPrincipal,
            register/login/refresh/logout
document/   upload, ingestion pipeline, SourceDocument, SourceDocumentRepository port +
            QdrantSourceDocumentRepository, controller
qa/         retrieval, grounded answer generation, citation mapping, controller
```

### Things that will bite you

**The entity is `SourceDocument`, not `Document`.** `org.springframework.ai.document.Document` is
the chunk type and appears in the same classes constantly.

**Two Qdrant collections, two very different shapes.** `vector_store` holds chunk embeddings and
is entirely Spring AI's — `QdrantVectorStoreAutoConfiguration` creates it at startup
(`spring.ai.vectorstore.qdrant.initialize-schema: true`, dimension 1536, cosine distance) and the
app only ever touches it through the injected `VectorStore` bean. `documents` is this app's own —
`QdrantCollectionsInitializer` creates it (a 1-dimensional dummy vector, since Qdrant requires
every point to have one but nothing here is ever searched by similarity, only filtered and
scrolled), and `QdrantSourceDocumentRepository` reads and writes it directly via the Qdrant Java
client. Ownership is tracked purely through chunk metadata (`ChunkMetadata`: `document_id`,
`owner_id`, `filename`, `page_number`, `chunk_index`), and chunk deletion uses a metadata filter on
`document_id`. If you add a field that citations need, it goes in chunk metadata — there is no
join available.

**Every Qdrant collection needs its filtered fields explicitly indexed, or a managed instance
rejects the query outright.** Local/self-hosted Qdrant tolerates filtering an un-indexed field (a
slower full scan); Qdrant Cloud (server 1.19, at least) returns
`INVALID_ARGUMENT: Index required but not found` instead. `QdrantCollectionsInitializer` creates a
keyword index for every field anything filters on — `document_id`/`owner_id` on `vector_store`,
`owner_id`/`checksum`/`status` on `documents` — on every startup (creating an index that already
exists is a harmless no-op). Add a new filtered field anywhere and it needs an index here too, or
it will work fine locally and throw in production.

**Qdrant-backed classes must be `@Component`, never `@Repository`.** Once a JPA starter is on the
classpath (it is, for `auth`), Spring's `PersistenceExceptionTranslationPostProcessor` intercepts
every `@Repository`-annotated bean and unconditionally rewraps any `IllegalArgumentException`/
`IllegalStateException` it throws as `InvalidDataAccessApiUsageException` — a JPA convention that
has nothing to do with `QdrantSourceDocumentRepository`, but the interceptor doesn't check that.
This is exactly the kind of thing that passes every test until a test asserts on the specific
exception type and starts failing with a confusingly-wrapped one instead.

**A document's metadata and its raw uploaded bytes are the same Qdrant point — but only until
ingestion finishes.** The `content` payload field (base64) replaces what used to be a separate S3
object addressed by a storage key — see `QdrantSourceDocumentRepository`. `IngestionService` reads
it once via `loadContent`, and once chunks exist in `vector_store` and the document is marked
`COMPLETED`, `clearContent` deletes just that payload key (`deletePayloadAsync`, not a point
delete) — there's nothing left that needs the original file, and no reason to keep paying to store
it. That also means deleting a document outright (`DocumentService.delete`) is one point delete,
not a row-plus-object cleanup, and that `content` must be excluded from every other read
(`findById`, `findAllByOwnerId`, ...) or listing/fetching a document's status pulls its (up to
~67 MB base64-encoded, while it still exists) file into memory for no reason. It also means a
status transition (`IngestionStatusWriter` → `SourceDocumentRepository.updateStatus`) **must** use
Qdrant's partial `setPayload`, never a full point `upsert` — an upsert replaces a point's entire
payload, which would silently wipe `content` (while it's still there) or resurrect stale metadata
(after it's gone) on every `PENDING → PROCESSING → COMPLETED` transition. `save` (the creation
path, in `DocumentService.upload`) is the only place a full-payload upsert is correct.

**Document dedupe has no database uniqueness backing it.** Postgres enforces a unique `email` at
the DB level again, but the per-owner checksum dedupe check in `DocumentService.upload` is a plain
Qdrant check-then-write with a real (if narrow) race window — two concurrent uploads of identical
content by the same user can both pass the check before either writes. Accepted trade-off of
keeping documents on Qdrant, not an oversight; there's nothing transactional to attach a real fix
to without moving documents to Postgres too.

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

**Ingestion runs `@Async` on a plain `@EventListener`, not `@TransactionalEventListener`.**
`DocumentService`/`IngestionService` are Qdrant-backed, not JPA, so there's no transaction to wait
on the way `auth` has one — `DocumentService.upload`'s Qdrant upsert is synchronous and
immediately visible, unlike a JPA row that only becomes visible to other connections after commit.
Status writes still go through `IngestionStatusWriter`, a separate bean, purely so the
mutate-then-persist shape reads the same at every call site — there is no transactional proxy to
bypass here the way there would be under JPA.

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

`QaService.ask` takes an optional `documentIds` list (empty/null means the whole corpus).
`askAboutDocument` is just `ask(question, ownerId, List.of(documentId))` — both skip the
corpus-status branching: every requested id must be owned by the caller (verified one at a time
via `DocumentService.get`, which throws `DocumentNotFoundException` for someone else's document —
existence is never leaked), and retrieval is scoped to exactly those documents via
`RetrievalService.retrieveForDocuments`, which builds a `document_id in [...]` filter expression —
if any id fails ownership, retrieval never runs at all.

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
- Flyway is **not** transitive from the JPA starter; `spring-boot-starter-flyway` plus
  `flyway-database-postgresql` are both declared explicitly. It only ever migrates `users` —
  `documents`/`vector_store` are Qdrant collections, entirely outside Flyway's reach.
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
(a `PostgreSQLContainer` for `users`, a `QdrantContainer` for document metadata and chunk
embeddings) with `StubAiConfiguration` replacing the chat and embedding models, so the whole
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
