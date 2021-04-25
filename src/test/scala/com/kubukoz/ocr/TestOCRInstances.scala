package com.kubukoz.ocr

object TestOCRInstances {

  def simple[F[_]](implicit SC: fs2.Compiler[F, F]): OCR[F] =
    _.through(fs2.text.utf8Decode[F]).compile.string

}
