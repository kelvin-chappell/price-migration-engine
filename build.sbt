import Dependencies._
import com.gu.riffraff.artifact.BuildInfo
import sbt.Keys.{description, name}

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion := "2.13.18"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-Xfatal-warnings"
)

ThisBuild / riffRaffUploadArtifactBucket := Option("riffraff-artifact")
ThisBuild / riffRaffUploadManifestBucket := Option("riffraff-builds")

val buildInfo = Seq(
  buildInfoPackage := "build",
  buildInfoKeys ++= {
    val buildInfo = BuildInfo(baseDirectory.value)
    Seq[BuildInfoKey](
      "buildNumber" -> buildInfo.buildIdentifier
    )
  }
)

// Shared so that every project with the assembly plugin (i.e. all of them, since it's declared in plugins.sbt)
// merges duplicate/conflicting jar entries (AWS SDK codegen-resources, module-info.class, netty version props,
// etc.) the same way - `sbt assembly` at the aggregate root runs this task on every subproject.
val commonAssemblyMergeStrategy = assembly / assemblyMergeStrategy := {
  /*
   * AWS SDK v2 includes a codegen-resources directory in each jar, with conflicting names.
   * This appears to be for generating clients from HTTP services.
   * So it's redundant in a binary artefact.
   */
  case PathList("codegen-resources", _*)                        => MergeStrategy.discard
  case PathList(ps @ _*) if ps.last == "module-info.class"      => MergeStrategy.discard
  case PathList(ps @ _*) if ps.last == "execution.interceptors" => MergeStrategy.filterDistinctLines
  case PathList("META-INF", "io.netty.versions.properties")     => MergeStrategy.discard
  case x                                                        =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

lazy val priceMigrationEngine = (project in file("."))
  .aggregate(
    dynamoDb,
    core,
    coreScala3,
    lambda,
    cohortTableCreationLambda,
    migrationLambda,
    subscriptionIdUploadLambda,
    salesforceNotificationDateUpdateLambda,
    salesforceAmendmentUpdateLambda,
    salesforcePriceRiseCreationLambda,
    estimationLambda,
    stateMachine
  )

lazy val dynamoDb = (project in file("dynamoDb"))
  .enablePlugins(RiffRaffArtifact, BuildInfoPlugin)
  .settings(
    name := "price-migration-engine-dynamo-db",
    description := "Cloudformation for price-migration-engine-dynamo-db",
    riffRaffPackageType := (baseDirectory.value / "cfn"),
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::DynamoDb",
    buildInfo,
  )

// Shared code (model, services, migrations) used by every lambda handler.
//
// ZIO's implicit `Trace` (and similar) values are derived via version-specific macros, so a Scala 3 lambda
// cannot simply reuse a Scala-2.13-compiled `core` on its classpath (mixing `..._2.13` and `..._3` artifacts of
// the same library on one classpath also fails outright, as sbt detects and rejects "conflicting cross-version
// suffixes"). Rather than migrating every handler and `core` in one atomic step, `core`'s sources are compiled
// twice from the same `core/` source directory: as `core` (Scala 2.13, for handlers not yet migrated) and as
// `coreScala3` (Scala 3, for handlers that have been migrated). Both must currently compile cleanly, which is
// why `core`'s source is restricted to constructs valid under both Scala versions. Once every handler depending
// on it is on Scala 3, the `core`/`coreScala3` split collapses back into a single Scala-3-only `core` project.
lazy val core = (project in file("core"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "price-migration-engine-core",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    libraryDependencies ++= Seq(
      zio,
      zioStreams,
      upickle,
      awsDynamoDb,
      awsLambda,
      awsS3,
      awsSQS,
      awsStateMachine,
      awsSecretsManager,
      http_sttp_client4_core,
      http_sttp_client4_zio,
      commonsCsv,
      slf4jNop % Runtime,
      munit % Test,
      zioTest % Test,
      zioTestSbt % Test,
      zioMock % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    description := "Shared model/services/migrations code for the Price Migration Engine lambdas",
    buildInfo,
    commonAssemblyMergeStrategy,
  )

// Scala 3 build of the exact same sources as `core` above - see the comment on `core` for why this exists.
lazy val coreScala3 = (project in file("core"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    scalaVersion := "3.3.6",
    target := baseDirectory.value / "target-scala3",
    // The same source directory is used by `core` (Scala 2.13); its tests already run there and use zio-test/
    // zio-mock, which aren't declared here (to avoid a `_3`/`_2.13` cross-version clash on this project's
    // classpath - see comment on `core`). Skip compiling/running that test source set again in this project.
    Test / unmanagedSourceDirectories := Nil,
    name := "price-migration-engine-core-scala3",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    libraryDependencies ++= Seq(
      zio,
      zioStreams,
      upickle,
      awsDynamoDb,
      awsLambda,
      awsS3,
      awsSQS,
      awsStateMachine,
      awsSecretsManager,
      http_sttp_client4_core,
      http_sttp_client4_zio,
      commonsCsv,
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Shared model/services/migrations code for the Price Migration Engine lambdas (Scala 3 build)",
    buildInfo,
    commonAssemblyMergeStrategy,
  )

lazy val lambda = (project in file("lambda"))
  .enablePlugins(RiffRaffArtifact)
  // "test->test" lets this module's tests reuse core's test-only helpers (e.g. Fixtures, TestLogging).
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "price-migration-engine-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    libraryDependencies ++= Seq(
      zio,
      zioStreams,
      upickle,
      awsDynamoDb,
      awsLambda,
      awsS3,
      awsSQS,
      awsStateMachine,
      awsSecretsManager,
      http_sttp_client4_core,
      http_sttp_client4_zio,
      commonsCsv,
      slf4jNop % Runtime,
      munit % Test,
      zioTest % Test,
      zioTestSbt % Test,
      zioMock % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    description := "Lambda jar for the Price Migration Engine",
    assemblyJarName := "price-migration-engine-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::Lambda",
    riffRaffArtifactResources += ((project.base / "cfn.yaml", "cfn/cfn.yaml")),
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project must
    // not also generate it, or its own fat jar would fail to assemble due to duplicate `build/BuildInfo$.class`.
    commonAssemblyMergeStrategy,
  )

// First lambda migrated to Scala 3, as a template for migrating the rest one at a time.
// See docs/scala-3-migration.md for the recipe to follow for the next lambda.
lazy val cohortTableCreationLambda = (project in file("cohortTableCreationLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(coreScala3)
  .settings(
    scalaVersion := "3.3.6", // Scala 3 LTS - matches `coreScala3` (see comment above `core`).
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-cohort-table-creation-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `coreScala3` (compile-scope project dependency). Redeclaring them here with `%%`
    // would risk resolving a different/duplicate version and clashing on the classpath ("Conflicting
    // cross-version suffixes"). `munit` has no such transitive zio/upickle dependency, so it's safe to use
    // its Scala 3 build directly here for this module's own tests.
    libraryDependencies ++= Seq(
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Lambda jar for the Price Migration Engine's cohort table creation handler (Scala 3)",
    assemblyJarName := "price-migration-engine-cohort-table-creation-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::CohortTableCreationLambda",
    // BuildInfo (`build.BuildInfo`) is generated once, by `coreScala3` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Second lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val migrationLambda = (project in file("migrationLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(coreScala3)
  .settings(
    scalaVersion := "3.3.6", // Scala 3 LTS - matches `coreScala3` (see comment above `core`).
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-migration-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `coreScala3` (compile-scope project dependency). Redeclaring them here with `%%`
    // would risk resolving a different/duplicate version and clashing on the classpath ("Conflicting
    // cross-version suffixes"). `munit` has no such transitive zio/upickle dependency, so it's safe to use
    // its Scala 3 build directly here for this module's own tests.
    libraryDependencies ++= Seq(
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Lambda jar for the Price Migration Engine's migration handler (Scala 3)",
    assemblyJarName := "price-migration-engine-migration-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::MigrationLambda",
    // BuildInfo (`build.BuildInfo`) is generated once, by `coreScala3` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Third lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val subscriptionIdUploadLambda = (project in file("subscriptionIdUploadLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(coreScala3)
  .settings(
    scalaVersion := "3.3.6", // Scala 3 LTS - matches `coreScala3` (see comment above `core`).
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-subscription-id-upload-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-*/commons-csv here: they are pulled transitively, at their
    // Scala 3 binary version (or, for commons-csv, plain Java, so no cross-version suffix at all), from
    // `coreScala3` (compile-scope project dependency). Redeclaring them here with `%%` would risk resolving a
    // different/duplicate version and clashing on the classpath ("Conflicting cross-version suffixes"). `munit`
    // has no such transitive zio/upickle dependency, so it's safe to use its Scala 3 build directly here for
    // this module's own tests.
    libraryDependencies ++= Seq(
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Lambda jar for the Price Migration Engine's subscription id upload handler (Scala 3)",
    assemblyJarName := "price-migration-engine-subscription-id-upload-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::SubscriptionIdUploadLambda",
    // BuildInfo (`build.BuildInfo`) is generated once, by `coreScala3` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Fourth lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val salesforceNotificationDateUpdateLambda = (project in file("salesforceNotificationDateUpdateLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(coreScala3)
  .settings(
    scalaVersion := "3.3.6", // Scala 3 LTS - matches `coreScala3` (see comment above `core`).
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-salesforce-notification-date-update-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `coreScala3` (compile-scope project dependency). Redeclaring them here with `%%`
    // would risk resolving a different/duplicate version and clashing on the classpath ("Conflicting
    // cross-version suffixes"). `munit` has no such transitive zio/upickle dependency, so it's safe to use
    // its Scala 3 build directly here for this module's own tests.
    libraryDependencies ++= Seq(
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Lambda jar for the Price Migration Engine's Salesforce notification date update handler (Scala 3)",
    assemblyJarName := "price-migration-engine-salesforce-notification-date-update-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::SalesforceNotificationDateUpdateLambda",
    // BuildInfo (`build.BuildInfo`) is generated once, by `coreScala3` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Fifth lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val salesforceAmendmentUpdateLambda = (project in file("salesforceAmendmentUpdateLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(coreScala3)
  .settings(
    scalaVersion := "3.3.6", // Scala 3 LTS - matches `coreScala3` (see comment above `core`).
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-salesforce-amendment-update-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `coreScala3` (compile-scope project dependency). Redeclaring them here with `%%`
    // would risk resolving a different/duplicate version and clashing on the classpath ("Conflicting
    // cross-version suffixes"). `munit` has no such transitive zio/upickle dependency, so it's safe to use
    // its Scala 3 build directly here for this module's own tests.
    libraryDependencies ++= Seq(
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Lambda jar for the Price Migration Engine's Salesforce amendment update handler (Scala 3)",
    assemblyJarName := "price-migration-engine-salesforce-amendment-update-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::SalesforceAmendmentUpdateLambda",
    // BuildInfo (`build.BuildInfo`) is generated once, by `coreScala3` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Sixth lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val salesforcePriceRiseCreationLambda = (project in file("salesforcePriceRiseCreationLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(coreScala3)
  .settings(
    scalaVersion := "3.3.6", // Scala 3 LTS - matches `coreScala3` (see comment above `core`).
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-salesforce-price-rise-creation-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `coreScala3` (compile-scope project dependency). Redeclaring them here with `%%`
    // would risk resolving a different/duplicate version and clashing on the classpath ("Conflicting
    // cross-version suffixes"). `munit` has no such transitive zio/upickle dependency, so it's safe to use
    // its Scala 3 build directly here for this module's own tests.
    libraryDependencies ++= Seq(
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Lambda jar for the Price Migration Engine's Salesforce price rise creation handler (Scala 3)",
    assemblyJarName := "price-migration-engine-salesforce-price-rise-creation-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::SalesforcePriceRiseCreationLambda",
    // BuildInfo (`build.BuildInfo`) is generated once, by `coreScala3` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Seventh lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val estimationLambda = (project in file("estimationLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(coreScala3)
  .settings(
    scalaVersion := "3.3.6", // Scala 3 LTS - matches `coreScala3` (see comment above `core`).
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-estimation-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `coreScala3` (compile-scope project dependency). Redeclaring them here with `%%`
    // would risk resolving a different/duplicate version and clashing on the classpath ("Conflicting
    // cross-version suffixes"). `munit` has no such transitive zio/upickle dependency, so it's safe to use
    // its Scala 3 build directly here for this module's own tests.
    libraryDependencies ++= Seq(
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Lambda jar for the Price Migration Engine's estimation handler (Scala 3)",
    assemblyJarName := "price-migration-engine-estimation-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::EstimationLambda",
    // BuildInfo (`build.BuildInfo`) is generated once, by `coreScala3` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

lazy val stateMachine = (project in file("stateMachine"))
  .enablePlugins(RiffRaffArtifact, BuildInfoPlugin)
  .settings(
    name := "price-migration-engine-state-machine",
    description := "Cloudformation for price migration state machine.",
    riffRaffPackageType := (baseDirectory.value / "cfn"),
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::StateMachine",
    buildInfo,
  )
