package pricemigrationengine.services

class EnvConfigTest extends munit.FunSuite {

  test("cohortTable.instance reads batchSize from the environment as an Int") {
    val config = EnvConfig.cohortTable.instance(name => if (name == "batchSize") Some("42") else None)
    assertEquals(config.batchSize, 42)
  }

  test("cohortTable.instance throws a RuntimeException when batchSize is missing") {
    intercept[RuntimeException] {
      EnvConfig.cohortTable.instance(_ => None)
    }
  }

  test("stage.instance reads the stage from the environment") {
    val config = EnvConfig.stage.instance(name => if (name == "stage") Some("CODE") else None)
    assertEquals(config.stage, "CODE")
  }

  test("stage.instance throws a RuntimeException when stage is missing") {
    intercept[RuntimeException] {
      EnvConfig.stage.instance(_ => None)
    }
  }

  test("emailSender.instance reads sqsEmailQueueName from the environment") {
    val config =
      EnvConfig.emailSender.instance(name => if (name == "sqsEmailQueueName") Some("queue-name") else None)
    assertEquals(config.sqsEmailQueueName, "queue-name")
  }

  test("cohortStateMachine.instance reads cohortStateMachineArn from the environment") {
    val config =
      EnvConfig.cohortStateMachine.instance(name => if (name == "cohortStateMachineArn") Some("arn") else None)
    assertEquals(config.stateMachineArn, "arn")
  }

  test("export.instance reads exportBucketName from the environment") {
    val config = EnvConfig.`export`.instance(name => if (name == "exportBucketName") Some("bucket") else None)
    assertEquals(config.exportBucketName, "bucket")
  }
}
