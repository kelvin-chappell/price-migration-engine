# Price Migration Engine

An orchestration engine (ZIO/Scala AWS Lambdas + a step function) that performs controlled price migrations for
subscriptions, plus a TypeScript tool for Android price rises.

## Build, test, lint

Scala side (sbt 1.x, Java Corretto 25, Scala 2.13 / Scala 3 mixed):

- Full check (matches CI): `sbt scalafmtCheckAll scalafmtSbtCheck test assembly`
- Run one module's tests: `sbt "core/test"`
- Run a single test: `sbt "core/testOnly pricemigrationengine.model.SomeSpec"`
- Format: `sbt scalafmtAll`

`android-price-rise/` is a separate Yarn/TypeScript project: `yarn install`, `npx eslint .`, `npx jest`.

## Architecture

- **`core`** — shared pure `model` code, ZIO `services` (each with a `Foo`/`FooLive` pair), and per-migration
  `migrations` logic. Also home to the shared `CohortHandler` trait every lambda extends.
- **`coreScala3`** — the same `core/` sources, compiled separately under Scala 3 for lambdas migrated to Scala 3
  (ZIO's macro-derived implicits mean Scala 2.13/3 builds can't share a classpath). See
  [docs/scala-3-migration.md](../docs/scala-3-migration.md) before touching `core` or migrating another lambda.
- **`lambda`** — most of the AWS Lambda handlers, sharing one fat jar. Depends on `core`.
- **`cohortTableCreationLambda`**, **`migrationLambda`**, **`subscriptionIdUploadLambda`** — handlers migrated to
  Scala 3 so far, each its own subproject/jar. Depends on `coreScala3`.
- **`dynamoDb`** / **`stateMachine`** — CloudFormation only, no application code.

Each lambda is an `AWS::Lambda::Function` resource in `lambda/cfn.yaml` (except `MigrationHandler`, deployed via
`stateMachine/cfn/cfn.yaml`); per-lambda deployment only differs by `Code.S3Key` (jar) and `Handler` (class).

## Key conventions

- Keep `model` pure/deterministic; put all effects behind `services` (ZIO interface + live implementation).
- `MigrationType(cohortSpec)` dispatch must declare all cases explicitly — no wildcard defaults (see
  [docs/coding-directives.md](../docs/coding-directives.md)).
- Prefer failing loudly over adding defensive/recovery logic — this is an unattended batch process; failures
  raise an alarm for an engineer to investigate.
