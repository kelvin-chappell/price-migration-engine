import Dependencies._
import com.gu.riffraff.artifact.BuildInfo
import sbt.Keys.{description, name}

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion := "3.3.6"

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
    lambda,
    cohortTableCreationLambda,
    migrationLambda,
    subscriptionIdUploadLambda,
    salesforceNotificationDateUpdateLambda,
    salesforceAmendmentUpdateLambda,
    salesforcePriceRiseCreationLambda,
    estimationLambda,
    amendmentLambda,
    notificationLambda,
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
// This used to be built twice - as Scala 2.13 `core` (for handlers not yet migrated) and Scala 3 `coreScala3`
// (for migrated handlers) - because ZIO's implicit `Trace` (and similar) values are derived via version-specific
// macros, so a Scala 3 lambda couldn't reuse a Scala-2.13-compiled `core`. Now that every handler has migrated
// to Scala 3 (see docs/scala-3-migration.md), that split has collapsed back into this single Scala 3 project.
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

lazy val lambda = (project in file("lambda"))
  .enablePlugins(RiffRaffArtifact, BuildInfoPlugin)
  .settings(
    name := "price-migration-engine-lambda",
    // Every handler has now migrated to its own Scala 3 subproject (see docs/scala-3-migration.md), so this
    // project no longer builds any Scala code. It only carries the CloudFormation template (cfn/cfn.yaml)
    // defining every handler's AWS::Lambda::Function/IAM resources, deployed under the same Riffraff project
    // name ("Retention::PriceMigrationEngine::Lambda") as before.
    description := "Cloudformation for the Price Migration Engine's lambdas",
    riffRaffPackageType := (baseDirectory.value / "cfn"),
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::Lambda",
    buildInfo,
  )

// First lambda migrated to Scala 3, as a template for migrating the rest one at a time.
// See docs/scala-3-migration.md for the recipe to follow for the next lambda.
lazy val cohortTableCreationLambda = (project in file("cohortTableCreationLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(core)
  .settings(
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-cohort-table-creation-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `core` (compile-scope project dependency). Redeclaring them here with `%%`
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
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Second lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val migrationLambda = (project in file("migrationLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(core)
  .settings(
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-migration-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `core` (compile-scope project dependency). Redeclaring them here with `%%`
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
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Third lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val subscriptionIdUploadLambda = (project in file("subscriptionIdUploadLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(core)
  .settings(
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-subscription-id-upload-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-*/commons-csv here: they are pulled transitively, at their
    // Scala 3 binary version (or, for commons-csv, plain Java, so no cross-version suffix at all), from
    // `core` (compile-scope project dependency). Redeclaring them here with `%%` would risk resolving a
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
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Fourth lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val salesforceNotificationDateUpdateLambda = (project in file("salesforceNotificationDateUpdateLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(core)
  .settings(
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-salesforce-notification-date-update-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `core` (compile-scope project dependency). Redeclaring them here with `%%`
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
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Fifth lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val salesforceAmendmentUpdateLambda = (project in file("salesforceAmendmentUpdateLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(core)
  .settings(
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-salesforce-amendment-update-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `core` (compile-scope project dependency). Redeclaring them here with `%%`
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
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Sixth lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val salesforcePriceRiseCreationLambda = (project in file("salesforcePriceRiseCreationLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(core)
  .settings(
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-salesforce-price-rise-creation-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `core` (compile-scope project dependency). Redeclaring them here with `%%`
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
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Seventh lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val estimationLambda = (project in file("estimationLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(core)
  .settings(
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-estimation-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `core` (compile-scope project dependency). Redeclaring them here with `%%`
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
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Eighth lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val amendmentLambda = (project in file("amendmentLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(core)
  .settings(
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-amendment-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `core` (compile-scope project dependency). Redeclaring them here with `%%`
    // would risk resolving a different/duplicate version and clashing on the classpath ("Conflicting
    // cross-version suffixes"). `munit` has no such transitive zio/upickle dependency, so it's safe to use
    // its Scala 3 build directly here for this module's own tests.
    libraryDependencies ++= Seq(
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Lambda jar for the Price Migration Engine's amendment handler (Scala 3)",
    assemblyJarName := "price-migration-engine-amendment-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::AmendmentLambda",
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project
    // must not also generate it, or its own fat jar would fail to assemble due to a duplicate class.
    commonAssemblyMergeStrategy,
  )

// Ninth lambda migrated to Scala 3. See docs/scala-3-migration.md for the recipe.
lazy val notificationLambda = (project in file("notificationLambda"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(core)
  .settings(
    scalacOptions += "-source:3.3", // -deprecation/-Xfatal-warnings already set at ThisBuild level.
    name := "price-migration-engine-notification-lambda",
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-handler" % "4.2.16.Final",
      "io.netty" % "netty-codec-base" % "4.2.16.Final",
      "io.netty" % "netty-codec" % "4.2.16.Final"
    ),
    // Deliberately NOT redeclaring zio/upickle/aws-* here: they are pulled transitively, at their Scala 3
    // binary version, from `core` (compile-scope project dependency). Redeclaring them here with `%%`
    // would risk resolving a different/duplicate version and clashing on the classpath ("Conflicting
    // cross-version suffixes"). `munit` has no such transitive zio/upickle dependency, so it's safe to use
    // its Scala 3 build directly here for this module's own tests.
    libraryDependencies ++= Seq(
      slf4jNop % Runtime,
      munit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    description := "Lambda jar for the Price Migration Engine's notification handler (Scala 3)",
    assemblyJarName := "price-migration-engine-notification-lambda.jar",
    riffRaffPackageType := assembly.value,
    riffRaffManifestProjectName := "Retention::PriceMigrationEngine::NotificationLambda",
    // BuildInfo (`build.BuildInfo`) is generated once, by `core` (used from LambdaLogging); this project
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
