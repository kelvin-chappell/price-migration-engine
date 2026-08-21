package pricemigrationengine.services

import pricemigrationengine.model.S3Failure
import software.amazon.awssdk.services.s3.model.{ObjectCannedACL, PutObjectResponse}
import zio.{IO, Scope, ZIO}

import java.io.{File, InputStream}

case class S3Location(bucket: String, key: String)

trait S3 {

  /** Returns the object's content stream. The caller is responsible for closing it (e.g. via
    * `scala.util.Using`).
    */
  def getObject(s3Location: S3Location): InputStream
  def putObject(
      s3Location: S3Location,
      localFile: File,
      cannedAcl: Option[ObjectCannedACL]
  ): PutObjectResponse
  def deleteObject(s3Location: S3Location): Unit
}

object S3 {

  /** ZIO-facing compatibility shim for not-yet-converted callers. Keeps the original `Scope`-managed resource
    * semantics by wrapping the plain, caller-closes `getObject` in `ZIO.fromAutoCloseable`.
    */
  def getObject(s3Location: S3Location): ZIO[S3, S3Failure, ZIO[Scope, S3Failure, InputStream]] =
    ZIO.environmentWith(env =>
      ZIO
        .fromAutoCloseable(ZIO.attempt(env.get.getObject(s3Location)))
        .mapError(ex => S3Failure(s"Failed to get $s3Location: $ex"))
    )

  def putObject(
      s3Location: S3Location,
      localFile: File,
      cannedAcl: Option[ObjectCannedACL]
  ): ZIO[S3, S3Failure, PutObjectResponse] =
    ZIO.environmentWithZIO(env =>
      ZIO
        .attempt(env.get.putObject(s3Location, localFile, cannedAcl))
        .mapError(ex => S3Failure(s"Failed to write s3 object $s3Location: ${ex.getMessage}"))
    )

  def deleteObject(s3Location: S3Location): ZIO[S3, S3Failure, Unit] =
    ZIO.environmentWithZIO(env =>
      ZIO
        .attempt(env.get.deleteObject(s3Location))
        .mapError(ex => S3Failure(s"Failed to delete s3 object $s3Location: ${ex.getMessage}"))
    )
}
