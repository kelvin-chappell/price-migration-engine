## Migrating off ZIO to direct-style Scala 3

**Status: planned.** This document is the spec and migration plan for removing ZIO from the codebase in favour
of plain synchronous, direct-style Scala 3. See [ADR-0001](adr/0001-drop-zio-for-direct-style.md) for the
rationale behind dropping ZIO. This document describes the target shape of the code and the recipe for getting
there one `core` service, then one lambda handler, at a time - the same incremental, validate-at-each-step
approach used in [scala-3-migration.md](scala-3-migration.md).

### Why this can't be done handler-by-handler in place

All 9 lambda handlers depend, directly or transitively, on `core`'s 11 services (`CohortTable`, `DynamoDBClient`,
`DynamoDBZIO`, `Zuora`, `SalesforceClient`, `S3`, `EmailSender`, `CohortStateMachine`, `CohortTableDdl`,
`CohortSpecTable`, `EnvConfig`), which are shared and currently all ZIO-based. A handler can't fully drop ZIO
until every `core` service it depends on has too - so, unlike the Scala 3 migration (which only needed a
compatible `core` build), this migration must convert `core`'s services first, one at a time, each kept callable
from still-ZIO handlers via a thin compatibility shim, before any handler is flipped. Interleaving service and
handler conversions would mean some handlers are mid-conversion while shared services are still changing
underneath them - higher risk of cross-lambda regressions. So the sequence is: **all services, then all
handlers, then remove the dependencies.**

### Target shape

| Concern | Today (ZIO) | Target (direct style) |
|---|---|---|
| Effect type | `ZIO[R, E, A]` / `IO[E, A]` / `Task[A]` | plain method calls returning `A` or `Unit`, or throwing |
| Errors | `sealed trait Failure` (28 subtypes) as ZIO's `E` channel | `Failure` subtypes become a `RuntimeException` hierarchy; thrown, not returned |
| Dependency injection | `ZLayer` composition, `.provide`/`.provideSome` per handler | plain constructor injection; one small "module" object per lambda that builds the dependency graph and exposes it to `handleRequest` |
| Retry (Zuora GETs, DDL continuous-backups) | `effect.retry(Schedule.exponential(...) && Schedule.recurs(n))` | hand-rolled `def retry[A](times: Int, delay: FiniteDuration)(f: => A): A` in `core` |
| Long-poll (Zuora job-status) | `effect.retry(Schedule.spaced(2.second) && Schedule.recurs(150))` polling until a condition | hand-rolled `def pollUntil[A](maxAttempts: Int, delay: FiniteDuration)(poll: => A)(done: A => Boolean): A`, separate from `retry` since the trigger is a business condition, not a thrown exception |
| DynamoDB pagination | `ZStream.unfoldZIO` over `QueryRequest`/`ScanRequest`, materialised via `runCount`/`runDrain` | a plain lazy `Iterator` wrapping the AWS SDK's own paginated response - keeps the existing lazy-pagination behaviour without a library |
| Resource management (S3 `InputStream`, STTP backend) | `ZIO.scoped` / `ZIO.acquireRelease` / `ZIO.fromAutoCloseable` | `scala.util.Using`. The DynamoDB client's existing "intentionally never shut down" behaviour is carried forward unchanged |
| HTTP client | STTP `HttpClientZioBackend` | STTP `HttpClientSyncBackend` - same request/response/JSON-codec code, only the backend changes |
| Logging | ZIO `Logging` service, used via `.tapBoth`/`.tapError` | plain trait `Logging { def info(msg: String): Unit; def error(msg: String, e: Throwable): Unit }`, still constructor-injected |
| Tests | `munit` (already used everywhere - `zio-test` is unused, confirmed 0 of 33 test files use `ZIOSpecDefault`/`assertZIO`) | unchanged; existing fakes (anonymous-class stubs) continue to work once the traits they implement are plain |
| `zio-mock` | 1 file, `core/src/test/scala/pricemigrationengine/service/MockCohortTable.scala`, unused by any test | deleted |

### Dependencies to remove from `build.sbt`

Once every handler is flipped: `zio`, `zioStreams`, `zioTest`/`zioTestSbt` (test scope), `zioMock` (test scope),
and the STTP ZIO backend artifact (`http_sttp_client4_zio`). `http_sttp_client4_core` (and STTP's request/JSON
codec modules) stay, since only the backend changes.

### Phase 1: migrate `core` services, one at a time

Convert each service from a ZIO trait + `ZLayer`-based `Live` implementation into a plain trait + a class/object
taking its dependencies as constructor parameters. While a service is mid-migration, wrap its new direct-style
implementation behind a thin `ZIO.attempt(liveCall(...))` shim at its existing ZIO call sites, so dependent
handlers (still ZIO-based) keep compiling and passing their existing `munit` tests unchanged. Remove the shim
only once every caller of that service has itself flipped to direct style (i.e. in Phase 2, per handler).

Convert services in dependency order (a service can't be validated standalone until everything it depends on is
already direct-style):

1. **Layer 0 - no service dependencies:**
   - `Logging` (`ConsoleLogging`, `LambdaLogging`) - becomes a plain trait, no deps.
   - `EnvConfig` and its subcomponents (`zuora`, `salesforce`, `cohortTable`, `stage`, `emailSender`,
     `cohortStateMachine`, `export`) - already just reads `System.getenv`/`EngineSecrets`; becomes plain
     constructors/case classes instead of `ZLayer` values.
2. **Layer 1 - depend only on `Logging`/config:**
   - `DynamoDBClient` (needs `Logging`)
   - `S3` (needs `Logging`) - the `getObject` `InputStream` moves from `ZIO.fromAutoCloseable` inside a `Scope`
     to `scala.util.Using` at each call site.
   - `CohortStateMachine` (needs `Logging` + its config)
   - `EmailSender` (needs `Logging` + its config)
   - `Zuora` (needs `Logging` + its config) - retry sites become calls to the new `retry`/`pollUntil` helpers;
     its STTP backend switches to `HttpClientSyncBackend`.
   - `SalesforceClient` (needs `Logging` + its config) - STTP backend switches to `HttpClientSyncBackend`.
3. **Layer 2 - depend on Layer 1 services:**
   - `DynamoDBZIO` (needs `DynamoDBClient` + `Logging`) - `ZStream.unfoldZIO` pagination becomes a lazy
     `Iterator`.
   - `CohortTableDdl` (needs `DynamoDBClient` + stage config + `Logging`) - its retry site becomes a call to the
     new `retry` helper.
   - `CohortSpecTable` (needs `DynamoDBClient` + stage config + `Logging`)
   - `CohortTable` (needs `DynamoDBZIO` + stage config + its config + `Logging`)

After each service, run `sbt test` to confirm the existing `munit` suite (including any hand-written fakes for
that service) still passes - no test rewriting should be needed at this stage, since call sites are still
ZIO-wrapped via the shim.

At the start of Phase 1, add the two retry helpers and delete `MockCohortTable.scala` (dead `zio-mock` code,
confirmed unreferenced by any test).

### Phase 2: flip each of the 9 handlers, one at a time

Once every `core` service a handler depends on is direct-style (with shims still in place), migrate that
handler:

1. Replace the handler's `.provideSome[Logging](...)`/`.provide(...)` layer-composition block with a small
   per-lambda "module" object that constructs the dependency graph via plain `new`/factory calls in dependency
   order, and exposes the wired services to `handleRequest`. This keeps `handleRequest` itself focused on
   business logic, matching the shape every handler already has today.
2. Replace the handler's use of `Runner.unsafeRun`/ZIO effect chaining with plain sequential calls; a thrown
   exception (previously a `Failure` in the `E` channel) propagates directly - AWS's Lambda Java runtime already
   turns an uncaught exception into the handler's failure response, so no new top-level try/catch translation is
   needed unless a handler currently does something with `Failure` beyond logging-then-rethrowing (none do, per
   the survey).
3. Remove the compatibility shims for any `core` service that no longer has any ZIO-based caller left.
4. Port the handler's existing `munit` test(s) - they already stub dependencies via plain anonymous-class fakes,
   so no test framework change is needed, just updating fakes to implement the now-plain trait signatures.
5. Validate: `sbt scalafmtCheckAll`, `sbt test`, `sbt assembly` - confirm the jar still builds and contains the
   right classes.

Suggested order (simplest dependency graphs first, building confidence before the more heavily-wired handlers):
`cohortTableCreationLambda` (3 services) → `migrationLambda` (4) → `subscriptionIdUploadLambda` →
`salesforceNotificationDateUpdateLambda` → `salesforceAmendmentUpdateLambda` →
`salesforcePriceRiseCreationLambda` (5) → `estimationLambda` → `amendmentLambda` → `notificationLambda` (7+,
most heavily wired, migrated last).

### Phase 3: remove the dependencies

Once all 9 handlers are flipped and no shims remain: remove `zio`, `zioStreams`, `zioTest`, `zioTestSbt`,
`zioMock`, and the STTP ZIO backend artifact from `build.sbt`. Run a full `sbt clean compile test assembly`
across all subprojects to confirm nothing was left depending on them.

### No CI changes expected

As with the Scala 3 migration, `ci.yml` runs `sbt ... test assembly`/`riffRaffUpload` at the aggregate root and
needs no changes to pick up the converted services/handlers or the removed dependencies.
