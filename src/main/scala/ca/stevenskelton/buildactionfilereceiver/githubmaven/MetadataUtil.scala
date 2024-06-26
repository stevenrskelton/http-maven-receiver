package ca.stevenskelton.buildactionfilereceiver.githubmaven

import ca.stevenskelton.buildactionfilereceiver.{AuthToken, FileUploadFormData, ResponseException}
import cats.effect.IO
import cats.effect.kernel.Resource
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.log4cats.Logger

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId, ZonedDateTime}
import scala.xml.{Elem, XML}

object MetadataUtil:

  private def fetchXML(uri: Uri, authToken: AuthToken)(using httpClient: Resource[IO, Client[IO]]): IO[Elem] =
    httpClient.use:
      client =>

        val request = Request[IO](
          Method.GET,
          uri,
          headers = Headers(Header.ToRaw.keyValuesToRaw("Authorization" -> s"token $authToken")),
        )
        client.expectOr[String](request):
          errorResponse =>
            val msg = s"${errorResponse.status.code} Could not fetch GitHub maven: $uri"
            IO.raiseError(ResponseException(errorResponse.status, msg))
        .map(XML.loadString)

  def fetchMetadata(fileUploadFormData: FileUploadFormData, allowAllVersions: Boolean)(using httpClient: Resource[IO, Client[IO]], logger: Logger[IO]): IO[MavenPackage] =
    MavenPackage.gitHubMavenArtifactPath(fileUploadFormData).map {
      gitHubMavenArtifactPath =>
        fetchXML(gitHubMavenArtifactPath / "maven-metadata.xml", fileUploadFormData.authToken)
          .map { xml =>
            if allowAllVersions then
              parseAllVersionMetadata(fileUploadFormData, xml).getOrElse:
                parseLatestVersionMetadata(fileUploadFormData, xml)
            else
              parseLatestVersionMetadata(fileUploadFormData, xml)
          }
          .flatMap:
            mavenPackage =>
              if fileUploadFormData.version != mavenPackage.version then
                val msg = s"Version ${fileUploadFormData.version} requested. Latest is ${mavenPackage.version}${mavenPackage.updated.fold("")(z => s" updated on ${z.toString}")}"
                IO.raiseError(ResponseException(Status.Conflict, msg))
              else if fileUploadFormData.version.endsWith("-SNAPSHOT") then
                fetchXML(gitHubMavenArtifactPath / fileUploadFormData.version / "maven-metadata.xml", fileUploadFormData.authToken)
                  .map(parseLatestSnapshotVersionMetadata(fileUploadFormData, _))
              else
                IO.pure(mavenPackage)
    }.getOrElse:
      val msg = s"Invalid package ${fileUploadFormData.user} | ${fileUploadFormData.repository} | ${fileUploadFormData.groupId} | ${fileUploadFormData.artifactId}"
      IO.raiseError(ResponseException(Status.BadGateway, msg))

  private def lastUpdated(xmlText: String): ZonedDateTime =
    LocalDateTime.parse(xmlText, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atZone(ZoneId.of("UTC"))

  private def parseLatestVersionMetadata(fileUploadFormData: FileUploadFormData, metadata: Elem): MavenPackage =
    MavenPackage(
      user = fileUploadFormData.user,
      repository = fileUploadFormData.repository,
      groupId = fileUploadFormData.groupId,
      artifactId = fileUploadFormData.artifactId,
      packaging = fileUploadFormData.packaging,
      version = (metadata \ "versioning" \ "latest").text,
      snapshotTimeIncrement = None,
      updated = Some(lastUpdated((metadata \ "versioning" \ "lastUpdated").text)),
    )

  private def parseAllVersionMetadata(fileUploadFormData: FileUploadFormData, metadata: Elem): Option[MavenPackage] =
    (metadata \ "versioning" \ "versions" \ "version")
      .find(_.text == fileUploadFormData.version)
      .map:
        version =>
          MavenPackage(
            user = fileUploadFormData.user,
            repository = fileUploadFormData.repository,
            groupId = fileUploadFormData.groupId,
            artifactId = fileUploadFormData.artifactId,
            packaging = fileUploadFormData.packaging,
            version = fileUploadFormData.version,
            snapshotTimeIncrement = None,
            updated = None,
          )

  private def parseLatestSnapshotVersionMetadata(fileUploadFormData: FileUploadFormData, metadata: Elem): MavenPackage =
    val node = (metadata \ "versioning" \ "snapshot").head
    val versionString = s"${(node \ "timestamp").text}-${(node \ "buildNumber").text}"
    val snapshotVersion = (metadata \ "version").text.replaceFirst("-SNAPSHOT", s"-$versionString")
    MavenPackage(
      user = fileUploadFormData.user,
      repository = fileUploadFormData.repository,
      groupId = fileUploadFormData.groupId,
      artifactId = fileUploadFormData.artifactId,
      packaging = fileUploadFormData.packaging,
      version = fileUploadFormData.version,
      snapshotTimeIncrement = Some(snapshotVersion),
      updated = Some(lastUpdated((metadata \ "versioning" \ "lastUpdated").text)),
    )

end MetadataUtil