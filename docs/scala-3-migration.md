## Migrating to Scala 3, one lambda at a time

### Why this can't be done handler-by-handler in place

Originally every lambda handler lived in one sbt subproject (`lambda`), sharing one `ThisBuild / scalaVersion`.
Scala version is a per-module setting, so Scala 2.13 and Scala 3 source files can't be mixed within a single sbt
project. To migrate lambdas incrementally, each migrated handler needs its own subproject with its own
`scalaVersion`.

The shared code used by every handler (`pricemigrationengine.model`, `.services`, `.migrations`, and the
`CohortHandler` trait) lives in a `core` subproject. This is where it gets more interesting: ZIO derives values
like `Trace` via macros that are implemented differently for Scala 2.13 and Scala 3. That means:

* A Scala 3 lambda can't simply put a Scala-2.13-compiled `core` on its classpath and call ZIO methods - the
  macro-derived implicits won't resolve (Scala 3 can't invoke old-style Scala 2 macros).
* Nor can a Scala 3 lambda declare its own `zio`/`upickle`/etc. (`_3` build) *and* depend on `core` (which brings
  in the `_2.13` build) - sbt rejects mixing `_2.13` and `_3` artifacts of the same library on one classpath
  ("Conflicting cross-version suffixes").

So `core`'s source is compiled **twice** from the same `core/` directory in `build.sbt`:

* `core` - Scala 2.13, used by `lambda` (handlers not yet migrated).
* `coreScala3` - Scala 3, used by lambdas that have been migrated.

Both must compile cleanly against the same source. In practice, this codebase's `core` sources already do, with
no changes required (no Scala-2-only syntax, no macros of its own). If a future change to `core` doesn't compile
under Scala 3, fix it there rather than diverging the two builds.

Once every handler depending on `core` has moved to Scala 3, delete `core` (the 2.13 build), `lambda`, and the
`coreScala3`/`core` split, leaving a single Scala 3 `core` and one subproject per (still separate) lambda, or
consolidate them if that's no longer needed.

### Recipe for migrating the next lambda

Using `cohortTableCreationLambda` (the first one migrated) as the template:

1. **Extract the handler** into its own subproject directory, e.g. `git mv lambda/src/main/scala/.../FooHandler.scala fooLambda/src/main/scala/pricemigrationengine/handlers/FooHandler.scala` (and its test, if any, similarly).
2. **Add the subproject** in `build.sbt`:
   - `.dependsOn(coreScala3)`, `scalaVersion := "3.3.6"` (or whatever Scala 3 LTS is current).
   - Do **not** redeclare `zio`/`upickle`/AWS SDK/etc. as `%%` library dependencies - they come transitively,
     at their Scala 3 build, from `coreScala3`. Redeclaring them risks a version/cross-suffix clash.
   - Libraries with no transitive ZIO/upickle dependency (e.g. `munit`) are safe to declare directly.
   - Copy the `assemblyJarName` / `riffRaffPackageType` / `riffRaffManifestProjectName` / `commonAssemblyMergeStrategy` pattern from an existing subproject; give it its own jar name.
   - Add the new subproject to `priceMigrationEngine`'s `.aggregate(...)`.
3. **Update the deploying `cfn.yaml`**: change the migrated function's `Code.S3Key` to the new jar's path
   (`membership/${Stage}/<new-project-name>/<new-project-name>.jar`, matching the `name := ...` setting).
   The `Handler` class path doesn't need to change. Most handlers' `AWS::Lambda::Function` resources live in
   `lambda/cfn.yaml`, but check first - e.g. `MigrationHandler`'s is in `stateMachine/cfn/cfn.yaml` instead
   (it's kicked off by a schedule rather than the step function, and deployed alongside the state machine).
4. **Fix any Scala 3 compile errors** in the handler itself (there weren't any for `CohortTableCreationHandler`;
   watch out for old-style wildcard imports if `-source` is set stricter than the default, and for anything
   relying on Scala 2 macros).
5. **Add/port tests** for the handler in the new subproject's test source set. Since this module intentionally
   doesn't depend on `zio-test`/`zio-mock` (see point 2), write handler tests with plain `munit` + a manually
   run `zio.Runtime` (see `CohortTableCreationHandlerTest` for the pattern) rather than `zio.test`. If the
   handler already had a test written with plain `munit` (some do, e.g. `SubscriptionIdUploadHandlerTest`), it
   usually ports over unchanged aside from swapping any `core`-test-only helper (e.g. `TestLogging`, which isn't
   available to a `coreScala3`-only project) for the thing it wraps directly (e.g. `ConsoleLogging.impl(...)`).
   Move any test resources the test loads (e.g. under `src/test/resources`) into the new subproject too. If a
   test uses `zio.test.TestClock`/`zio.test.testEnvironment` to control `Clock.instant` (etc.) for a fixed-time
   assertion, replace it with a hand-rolled `Clock` implementation and `ZIO#withClock`, e.g.:
   ```scala
   def stubClock(fixedInstant: Instant): Clock = new Clock {
     export Clock.ClockLive.{instant as _, *}
     override def instant(implicit trace: zio.Trace): UIO[Instant] = ZIO.succeed(fixedInstant)
   }
   // ...
   handler.main(input).withClock(stubClock(fixedInstant)).provideLayer(...)
   ```
   (see `SalesforceNotificationDateUpdateHandlerTest`). The Scala 3 `export ... {member as _, *}` clause
   delegates every other `Clock` method to the real `Clock.ClockLive`, so only the overridden method needs
   reimplementing. Avoid naming the fixed instant the same as any exported member (e.g. `currentTime`) - it'll
   shadow the export and cause an "ambiguous overload" error.
6. **Validate**: `sbt scalafmtCheckAll` (the new subproject's sources are covered by the `scala3` dialect
   `fileOverride` in `.scalafmt.conf`), `sbt test`, `sbt assembly` - confirm the new jar contains only the
   migrated handler's classes and the old `lambda` jar no longer does.

No CI changes are needed: `ci.yml` runs `sbt ... test assembly`/`riffRaffUpload` at the aggregate root, which
picks up new subprojects automatically.
