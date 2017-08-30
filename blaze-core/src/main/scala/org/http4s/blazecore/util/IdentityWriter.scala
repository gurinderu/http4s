package org.http4s
package blazecore
package util

import java.nio.ByteBuffer

import cats.effect._
import cats.implicits._
import fs2._
import org.http4s.blaze.pipeline.TailStage
import org.http4s.util.chunk._
import org.http4s.util.StringWriter
import org.log4s.getLogger

import scala.concurrent.{ExecutionContext, Future}

private[http4s] class IdentityWriter[F[_]](size: Long, out: TailStage[ByteBuffer])
                          (implicit protected val F: Effect[F], protected val ec: ExecutionContext)
    extends Http1Writer[F] {

  private[this] val logger = getLogger(classOf[IdentityWriter[F]])
  private[this] var headers: ByteBuffer = null

  private var bodyBytesWritten = 0L

  private def willOverflow(count: Long) =
    if (size < 0L) false
    else count + bodyBytesWritten > size

  def writeHeaders(headerWriter: StringWriter): Future[Unit] = {
    headers = Http1Writer.headersToByteBuffer(headerWriter.result)
    FutureUnit
  }

  protected def writeBodyChunk(chunk: Chunk[Byte], flush: Boolean): Future[Unit] =
    if (willOverflow(chunk.size.toLong)) {
      // never write past what we have promised using the Content-Length header
      val msg = s"Will not write more bytes than what was indicated by the Content-Length header ($size)"

      logger.warn(msg)

      val reducedChunk = chunk.take(size - bodyBytesWritten).toChunk
      writeBodyChunk(reducedChunk, flush = true) >> Future.failed(new IllegalArgumentException(msg))
    } else {
      val b = chunk.toByteBuffer

      bodyBytesWritten += b.remaining

      if (headers != null) {
        val h = headers
        headers = null
        out.channelWrite(h::b::Nil)
      }
      else out.channelWrite(b)
    }

  protected def writeEnd(chunk: Chunk[Byte]): Future[Boolean] = {
    val total = bodyBytesWritten + chunk.size

    if (size < 0 || total >= size) writeBodyChunk(chunk, flush = true).
      map(Function.const(size < 0)) // require close if infinite
    else {
      val msg = s"Expected `Content-Length: $size` bytes, but only $total were written."

      logger.warn(msg)

      writeBodyChunk(chunk, flush = true) flatMap {_ =>
        Future.failed(new IllegalStateException(msg))
      }
    }
  }
}
