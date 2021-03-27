package com.kubukoz.filesource

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.MonadCancelThrow
import cats.implicits._
import com.kubukoz.dropbox
import com.kubukoz.dropbox.Dropbox
import com.kubukoz.shared.FileData
import com.kubukoz.shared.Path
import fs2.Stream
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.Logger
import org.http4s.client.middleware.ResponseLogger

import util.chaining._
import cats.effect.ApplicativeThrow
import com.kubukoz.dropbox.FileDownload
import org.http4s.MediaType

object Demo extends IOApp.Simple {

  def run: IO[Unit] = BlazeClientBuilder[IO](runtime.compute)
    .stream
    .map(
      Logger.colored[IO](
        logHeaders = true,
        // For now there doesn't seem to be a way to hide body logging
        logBody = false,
        // https://github.com/http4s/http4s/issues/4647
        responseColor = ResponseLogger.defaultResponseColor _,
        logAction = Some(s => IO.println(s)),
      )
    )
    .flatMap { implicit c =>
      implicit val drop = Dropbox
        .instance(System.getenv("DROPBOX_TOKEN"))

      FileSource
        .dropboxInstance[IO]
        .streamFolder(
          Path("tony bullshitu/ayy")
        )
        .evalMap { file =>
          file
            .content
            .chunks
            .map(_.size)
            .compile
            .foldMonoid
        }
    }
    .take(5)
    .showLinesStdOut
    .compile
    .drain

}

trait FileSource[F[_]] {
  def streamFolder(rawPath: Path): Stream[F, FileData[F]]
}

object FileSource {

  def dropboxInstance[F[_]: Dropbox: MonadCancelThrow]: FileSource[F] = rawPath =>
    Stream
      .eval(dropbox.Path.parse(rawPath.value).leftMap(new Throwable(_)).liftTo[F])
      .flatMap { path =>
        Dropbox
          .paginate {
            case None         => Dropbox[F].listFolder(path, recursive = true)
            case Some(cursor) => Dropbox[F].listFolderContinue(cursor)
          }
      }
      .collect { case f: dropbox.FileMetadata.NormalFile => f }
      .flatMap(Dropbox[F].download(_).pipe(Stream.resource(_)))
      .evalMap(toFileData[F])

  def toFileData[F[_]: ApplicativeThrow](fd: FileDownload[F]): F[FileData[F]] = FileData(
    content = fd.data,
    name = "example.png",
    mediaType = MediaType.image.png, /* todo use metadata */
  ).pure[F]

}
