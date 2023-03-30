# http-maven-receiver
HTTP server that receives artifact uploads and verifies MD5 against Maven.

### Why would you use this?

Use Ansible in your Github Actions instead of this. Ansible has secure file upload and lots of plugins for simple server-side actions.
Downsides of Ansible:
- need SSH running
- SSH user triggers server-side actions
- SSH private key needs to be in Github

If you are performing server-side actions after the file is uploaded, and don't want to grant Ansible user needs permissions to perform them, the actions need to be separate scripts outside of Ansible.  This project is basically those server-side scripts, written in Java/JVM, with a small HTTP server attached.

Upload permissions are based around the ability to publish to Github Packages Maven.  Server-side permissions are isolated on the server.

## Two Parts

SBT build tasks
- publishAssemblyToGithubPackages: uploads compiled code to Github Packages (Maven)
- uploadAssemblyByPost: uploads compiled code to your server (HTTP POST)

HTTP Upload Server
- built on Akka, handles HTTP POST
- validates upload is latest version in Maven, and has correct MD5 checksum
- performs any custom server-side tasks, such as deployment and restarting

![Request Flow](./requests.drawio.svg)

## Project Install

(These assume you are using _"com.eed3si9n" % "sbt-assembly"_ to create uber jars)

- Copy `publishAssemblyToGithubPackages.sbt` and `uploadAssemblyByPost.sbt` to the root directory of your project.
- Copy `upload-assembly-to-maven-post.yml` to the `.github/workflows` folder in your project.
- In `upload-assembly-to-maven-post.yml` set `POST_URI` to the URL you want to upload to, eg:
```
POST_URI="http://yourdomain.com:8080/upload"
```

Running this Github Action will compile your code, upload the artifact to Github Packages for the project, and then upload the file to your `POST_URI` destination.

## Server-side Receiver Install

Compile this Scala project, and run on your server. 

You will need to specify the IP address to bind the server to, and what port to use.By default, this project will use port 8080.

Set the values in `application.conf`, or use command line arguments to set them, eg:
```
java -Dhttp-maven-receiver.host="192.168.0.1" -jar http-maven-receiver-assembly-0.1.0-SNAPSHOT.jar
```

### Command Line Params

`http-maven-receiver.host` : Host/IP address to bind to.  _Required_
`http-maven-receiver.port` : Port to bind to. _Default = 8080_
`http-maven-receiver.file-directory` : Directory to upload to. _Default = "./files"_
`http-maven-receiver.max-upload-size` : Maximum file size to handle. _Default = 1M_

## Post Upload Tasks

In Main.scala, the ArtifactUploadRoute takes a `Seq[AllowedGithubUser]` as a parameter.

Only uploads from these Github userid repositories will be allowed.  AllowedGithubUser defines:

```
def postHook(file: File): Future[Done]
```

This can be used to perform any actions on the uploaded `File`.
A simple example would be to move this file out of the upload folder to somewhere else.

eg:
```
sys.process.Process("sudo -- mv ${file.getAbsolutePath} /home/hosted/").!
```
