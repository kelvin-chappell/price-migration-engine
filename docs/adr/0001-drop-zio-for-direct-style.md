# Drop ZIO in favour of direct-style Scala 3

**Status:** accepted

Every lambda handler and `core` service is built on ZIO's `ZIO[R, E, A]` effect type, wired together with
`ZLayer`. In practice the codebase doesn't use most of what that buys: no fibers, no `Ref`/`Queue`, no
`.fork`/`.zipPar`/`.foreachPar` anywhere in main code, and the typed error channel (28 `Failure` subtypes) is
never inspected at any call site — every handler just logs and rethrows via `Runner.unsafeRun`. `ZStream` is
used only for DynamoDB pagination and is fully materialised (`runCount`/`runDrain`) wherever it's consumed.
`Schedule`-based retry appears at exactly 4 call sites. The `ZLayer` dependency graphs each handler assembles
are shallow (3-7 services, no more than 3 layers deep). `zio-test` is unused (all 33 test files are `munit`);
the one `zio-mock` usage is dead code.

We're dropping ZIO and moving to plain synchronous, direct-style Scala 3: services become plain
constructor-injected classes/objects, `Failure` becomes a thrown exception hierarchy, `Schedule` is replaced by
two small hand-rolled helpers (`retry`, `pollUntil`), `ZStream` pagination becomes a lazy `Iterator`, and
`ZIO.scoped`/bracket becomes `scala.util.Using`. This is a simplicity/maintainability call, not a response to
any concrete pain point or forcing function - the trade-off is giving up ZIO's typed effect tracking and
composability in exchange for code that reads as plain sequential Scala, which better matches how this codebase
actually behaves today. See [direct-style-migration.md](../direct-style-migration.md) for the full target
architecture and migration plan.

## Considered alternatives

- **Cats Effect `IO` or bare `Future`**: would still be an effect-monad swap, not direct style, and wouldn't
  address the actual complaint (unused ZIO machinery) - only trades one wrapper for another.
- **Structured-concurrency direct-style library (e.g. Ox)**: adds a dependency to get typed short-circuiting and
  structured concurrency that this codebase doesn't currently need (no fibers/parallelism anywhere in main
  code), so the added machinery wouldn't earn its keep either.
