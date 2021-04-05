package com.kubukoz.ocrapi

import java.nio.file.Paths

import cats.effect.Concurrent
import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import com.kubukoz.shared.FileData
import fs2.io.file.Files
import io.circe.Json
import org.http4s.Header
import org.http4s.MediaType
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.client.middleware.RequestLogger
import org.http4s.client.middleware.ResponseLogger
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.implicits._
import org.http4s.multipart.Multipart
import org.http4s.multipart.Part
import org.typelevel.ci.CIString
import com.kubukoz.shared.FileMetadata
import ciris.Secret

object Demo extends IOApp.Simple {

  def run: IO[Unit] =
    BlazeClientBuilder[IO](runtime.compute)
      .stream
      .map(RequestLogger(logHeaders = true, logBody = false, logAction = Some(IO.println(_))))
      .map(ResponseLogger(logHeaders = true, logBody = true, logAction = Some(IO.println(_))))
      .evalMap { implicit client =>
        IO.defer {
          val p = Paths.get("example.png")
          val src = FileData(Files[IO].readAll(p, 4096), FileMetadata("example.png", MediaType.image.png))

          val api = OCRAPI.instance[IO](Secret(System.getenv("OCRAPI_TOKEN")))

          api.decode(src)
        }
      }
      .compile
      .drain

}

trait OCRAPI[F[_]] {
  //todo: this is gonna be trouble, for >10MB they'd rather take a file URL
  //possible way to do this: before downloading the file from dropbox,
  //check the size and possibly forward the bytes to a file hosting service (S3? free alternatives? imgur?)
  //and call this api with an URL instead
  def decode(file: FileData[F]): F[Result]
}

object OCRAPI {
  def apply[F[_]](implicit F: OCRAPI[F]): OCRAPI[F] = F

  def instance[F[_]: Concurrent: Client](token: Secret[String]): OCRAPI[F] = new OCRAPI[F] with Http4sDsl[F] with Http4sClientDsl[F] {

    private val client: Client[F] = Client[F] { request =>
      implicitly[Client[F]].run(
        request
          .putHeaders(Header.Raw(CIString("apikey"), token.value))
      )
    } /* todo retries? */

    //todo: needs file name/type - to check: do we have mimetype in dropbox response?
    def decode(file: FileData[F]): F[Result] =
      client
        .expect[Json] {
          val body = Multipart[F](
            Vector(
              Part.formData("language", "pol"),
              Part.fileData[F]("file", file.metadata.name, file.content, `Content-Type`(file.metadata.mediaType)),
            )
          )

          POST(uri"https://api.ocr.space/parse/image")
            .withEntity(body)
            //this is a workaround, I think the EntityEncoder instance of Multipart should be doing this
            .putHeaders(body.headers.headers)
        }
        .flatMap { body =>
          //todo: side effect
          println(body)
          body.as[Result].liftTo[F]
        }

  }

}
