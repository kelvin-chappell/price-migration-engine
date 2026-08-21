package pricemigrationengine.services

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  DeleteObjectRequest,
  GetObjectRequest,
  ListObjectsRequest,
  ObjectCannedACL,
  PutObjectRequest,
  PutObjectResponse,
  S3Object
}
import zio.{ZIO, ZLayer}

import java.io.File
import scala.jdk.CollectionConverters._

object S3Live {

  /** Plain, direct-style implementation, backed by the shared `AwsClient.s3` client. Throws on failure.
    *
    * Note: `getObject` returns the raw `InputStream` - the caller is responsible for closing it (e.g. via
    * `scala.util.Using`), matching the target-shape decision to move S3 resource management from
    * `ZIO.fromAutoCloseable`/`Scope` to `scala.util.Using` at each direct-style call site.
    */
  def instance(logging: Logging, s3: S3Client = AwsClient.s3): S3 = new S3 {

    override def getObject(s3Location: S3Location) = {
      val getObjectRequest = GetObjectRequest.builder.bucket(s3Location.bucket).key(s3Location.key).build()
      s3.getObject(getObjectRequest)
    }

    override def putObject(
        s3Location: S3Location,
        localFile: File,
        cannedAcl: Option[ObjectCannedACL]
    ): PutObjectResponse = {
      val requestWithoutAcl = PutObjectRequest.builder.bucket(s3Location.bucket).key(s3Location.key)
      val putObjectRequest = cannedAcl.fold(requestWithoutAcl)(requestWithoutAcl.acl).build()
      val requestBody = RequestBody.fromFile(localFile)
      s3.putObject(putObjectRequest, requestBody)
    }

    override def deleteObject(s3Location: S3Location): Unit = {
      val listObjectsRequest = ListObjectsRequest.builder.bucket(s3Location.bucket).prefix(s3Location.key).build()
      def deleteObjectRequest(s3Object: S3Object) =
        DeleteObjectRequest.builder.bucket(s3Location.bucket).key(s3Object.key).build()
      val listObjectsResponse = s3.listObjects(listObjectsRequest)
      listObjectsResponse.contents.asScala.foreach { obj =>
        s3.deleteObject(deleteObjectRequest(obj))
        logging.info(s"Deleted $obj")
      }
    }
  }

  /** ZIO-facing compatibility shim for not-yet-converted callers. */
  val impl: ZLayer[Logging, Nothing, S3] =
    ZLayer.fromZIO(ZIO.service[Logging].map(logging => instance(logging)))
}
