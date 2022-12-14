package ca.stevenskelton.httpmavenreceiver

import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{complete, completeOrRecoverWith, extractRequestContext, post}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import akka.http.scaladsl.server.directives.MarshallingDirectives.{as, entity}
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Flow}
import com.typesafe.scalalogging.Logger

import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.util.{Failure, Success}

case class ArtifactUpload(httpExt: HttpExt,
                          directory: Path,
                          createHooks: ArtifactUpload => RequestHooks,
                          githubToken: Option[String] = None,
                         )(implicit val logger: Logger) {

  implicit val materializer: Materializer = Materializer(httpExt.system)

  def successfulResponseBody(githubPackage: GithubPackage, fileInfo: FileInfo, destinationFile: File, destinationFileMD5: String): HttpResponse = {
    val responseBody = s"Successfully saved upload of ${fileInfo.fileName}, ${Utils.humanFileSize(destinationFile)}, MD5 $destinationFileMD5"
    HttpResponse(StatusCodes.OK, Nil, HttpEntity(ContentTypes.`text/plain(UTF-8)`, responseBody))
  }

  val releasesPost: Route = post {
    entity(as[Multipart.FormData]) { formData =>
      extractRequestContext { ctx =>
        val hooks = createHooks(this)
        val uploadedF = FileUploadDirectives.parseFormData(formData, ctx).flatMap {
          o =>
            hooks.preHook(o._1, o._2, o._3, ctx).flatMap {
              case (githubPackage, fileInfo, bytes) =>
                val tmpFile = File.createTempFile(System.currentTimeMillis.toString, ".tmp", directory.toFile)
                val digest = MessageDigest.getInstance("MD5")
                bytes
                  .via(Flow.fromFunction {
                    byteString =>
                      digest.update(byteString.toArray, 0, byteString.size)
                      byteString
                  })
                  .runWith(FileIO.toPath(tmpFile.toPath))
                  .flatMap { ioResult =>
                    ioResult.status match {
                      case Success(_) =>
                        val uploadMD5 = Utils.byteArrayToHexString(digest.digest)
                        hooks.tmpFileHook(tmpFile, uploadMD5).flatMap {
                          dest =>
                            val response = successfulResponseBody(githubPackage, fileInfo, dest, uploadMD5)
                            hooks.postHook(response)
                        }
                      case Failure(ex) => Future.failed(ex)
                    }
                  }
                  .recoverWith {
                    case ex =>
                      logger.error("Upload IOResult failed", ex)
                      tmpFile.delete()
                      Future.failed(ex)
                  }
            }
        }
        completeOrRecoverWith(uploadedF) {
          case UserMessageException(statusCode, userMessage) => complete(statusCode, HttpEntity(ContentTypes.`text/plain(UTF-8)`, userMessage))
        }
      }
    }
  }
}