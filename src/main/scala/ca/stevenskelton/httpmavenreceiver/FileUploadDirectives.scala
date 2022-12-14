package ca.stevenskelton.httpmavenreceiver

import akka.Done
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.directives.FileInfo
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

/**
 * Modified code from akka.http.scaladsl.server.directives.FileUploadDirectives.fileUpload
 * Modified to:
 *  - parse form fields preceding file into Map
 *    (these need to be preceding the file part so they can be read before upload is allowed to start)
 */
object FileUploadDirectives {

  def parseFormData(formData: Multipart.FormData, ctx: RequestContext): Future[(Map[String, String], FileInfo, Source[ByteString, Any])] = {
    import ctx.{executionContext, materializer}

    val formFields = ListBuffer[(String, String)]()

    // We complete the directive through this promise as soon as we encounter the
    // selected part. This way the inner directive can consume it, after which we will
    // proceed to consume the rest of the request, discarding any follow-up parts.
    val done = Promise[(Map[String, String], FileInfo, Source[ByteString, Any])]()

    // Streamed multipart data must be processed in a certain way, that is, before you can expect the next part you
    // must have fully read the entity of the current part.
    // That means, we cannot just do `formData.parts.runWith(Sink.seq)` and then look for the part we are interested in
    // but instead, we must actively process all the parts, regardless of whether we are interested in the data or not.
    formData.parts
      .mapAsync(parallelism = 1) {
        case part if !done.isCompleted && part.filename.isDefined && part.name == GithubPackage.FileUploadFieldName =>
          val data = (formFields.toMap, FileInfo(part.name, part.filename.get, part.entity.contentType), part.entity.dataBytes)
          done.success(data)
          Future.successful(Done)
        case part if part.entity.contentType == ContentTypes.`text/plain(UTF-8)` =>
          Utils.sinkToString(part.entity.dataBytes).map {
            keyValue => formFields.addOne((part.name, keyValue))
          }
        case part =>
          part.entity.discardBytes().future
      }
      .runWith(Sink.ignore)
      .onComplete {
        case Success(Done) =>
          if (done.isCompleted)
            () // OK
          else
            done.failure(UserMessageException(StatusCodes.BadRequest, GithubPackage.FormErrorMessage))
        case Failure(cause) =>
          if (done.isCompleted)
            () // consuming the other parts failed though we already started processing the selected part.
          else
            done.failure(cause)
      }

    done.future
  }

}
