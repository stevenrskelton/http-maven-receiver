package ca.stevenskelton.buildactionfilereceiver

import ca.stevenskelton.buildactionfilereceiver.githubmaven.MavenPackage
import cats.effect.testing.scalatest.AsyncIOSpec
import fs2.io.file.Path
import org.http4s.Status
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

class PostUploadActionSpec extends AsyncFreeSpec with Matchers with AsyncIOSpec {

  private val destinationFile = Path("src/test/resources/postuploadactions/destinationfile.jar")
  private val mavenPackage = new MavenPackage(
    user = "gh-user",
    repository = "gh-project",
    groupId = "gh.groupid",
    artifactId = "test-file",
    packaging = "extension",
    version = "1.0.1",
    snapshotTimeIncrement = None,
    updated = None,
  )
  private val workingDirectory = Path("src/test/resources/postuploadactions").absolute

  "run" - {
    "populate environmental variables" in {
      given logger: RecordingLogger = RecordingLogger()

      val postUploadAction = PostUploadAction("./echoenv.sh", workingDirectory)
      postUploadAction.run(destinationFile, mavenPackage).unsafeRunSync()
      val log = logger.lines
      assert(log.length == 10)
      assert(log(0) == "Starting post upload action for destinationfile.jar")
      assert(log(1) == Path("").absolute.toString + "/src/test/resources/postuploadactions")
      assert(log(2) == mavenPackage.user)
      assert(log(3) == mavenPackage.repository)
      assert(log(4) == mavenPackage.groupId)
      assert(log(5) == mavenPackage.artifactId)
      assert(log(6) == mavenPackage.packaging)
      assert(log(7) == mavenPackage.version)
      assert(log(8) == "destinationfile.jar")
      assert(log(9) == "Completed post upload action for destinationfile.jar")
    }

    "handle error" in {
      given logger: RecordingLogger = RecordingLogger()

      val postUploadAction = PostUploadAction("./error.sh", workingDirectory)
      val ex = intercept[ResponseException](postUploadAction.run(destinationFile, mavenPackage).unsafeRunSync())
      assert(ex.status == Status.InternalServerError)

      val log = logger.lines
      assert(log.length == 3)
      assert(log(0) == "Starting post upload action for destinationfile.jar")
      assert(log(1).endsWith("/./error.sh: line 1: cd: hi: No such file or directory"))
      assert(log(2) == "ca.stevenskelton.buildactionfilereceiver.ResponseException: Failed post upload action for destinationfile.jar")
    }
  }

}
