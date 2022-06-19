/*
 * Copyright 2013 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package servlet

import cats.effect._
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.syntax.all._
import fs2._
import jakarta.servlet.ReadListener
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.log4s.getLogger

import java.util.Arrays

/** Determines the mode of I/O used for reading request bodies and writing response bodies.
  */
sealed trait ServletIo[F[_]] {
  def requestBody(
      servletRequest: HttpServletRequest,
      dispatcher: Dispatcher[F],
  ): Stream[F, Byte]

  def bodyWriter(servletResponse: HttpServletResponse, dispatcher: Dispatcher[F])(
      response: Response[F]
  ): F[Unit]
}

/** Use standard blocking reads and writes.
  *
  * This is more CPU efficient per request than [[NonBlockingServletIo]], but is likely to
  * require a larger request thread pool for the same load.
  */
final case class BlockingServletIo[F[_]](chunkSize: Int)(implicit F: Sync[F]) extends ServletIo[F] {
  override def requestBody(
      servletRequest: HttpServletRequest,
      dispatcher: Dispatcher[F],
  ): EntityBody[F] =
    io.readInputStream[F](F.pure(servletRequest.getInputStream), chunkSize)

  override def bodyWriter(
      servletResponse: HttpServletResponse,
      dispatcher: Dispatcher[F],
  )(response: Response[F]): F[Unit] = {
    val out = servletResponse.getOutputStream
    val flush = response.isChunked
    response.body.chunks
      .evalTap { chunk =>
        Sync[F].blocking {
          // Avoids copying for specialized chunks
          val byteChunk = chunk.toArraySlice
          out.write(byteChunk.values, byteChunk.offset, byteChunk.length)
          if (flush)
            servletResponse.flushBuffer()
        }
      }
      .compile
      .drain
  }
}

/** Use non-blocking reads and writes.  Available only on containers that support Servlet 3.1.
  *
  * This can support more concurrent connections on a smaller request thread pool than [[BlockingServletIo]],
  * but consumes more CPU per request.  It is also known to cause IllegalStateExceptions in the logs
  * under high load up through  at least Tomcat 8.0.15.  These appear to be harmless, but are
  * operationally annoying.
  */
final case class NonBlockingServletIo[F[_]](chunkSize: Int)(implicit F: Async[F])
    extends ServletIo[F] {
  private[this] val logger = getLogger

  /* The queue implementation is influenced by ideas in jetty4s
   * https://github.com/IndiscriminateCoding/jetty4s/blob/0.0.10/server/src/main/scala/jetty4s/server/HttpResourceHandler.scala
   */
  override def requestBody(
      servletRequest: HttpServletRequest,
      dispatcher: Dispatcher[F],
  ): Stream[F, Byte] = {
    sealed trait Read
    final case class Bytes(chunk: Chunk[Byte]) extends Read
    case object End extends Read
    final case class Error(t: Throwable) extends Read

    Stream.eval(F.delay(servletRequest.getInputStream)).flatMap { in =>
      Stream.eval(Queue.bounded[F, Read](4)).flatMap { q =>
        val readBody = Stream.exec(F.delay(in.setReadListener(new ReadListener {
          var buf: Array[Byte] = _
          unsafeReplaceBuffer()

          def unsafeReplaceBuffer() =
            buf = new Array[Byte](chunkSize)

          def onDataAvailable(): Unit = {
            def loopIfReady =
              F.delay(in.isReady()).flatMap {
                case true => go
                case false => F.unit
              }

            def go: F[Unit] =
              F.delay(in.read(buf)).flatMap {
                case len if len == chunkSize =>
                  // We used the whole buffer.  Replace it new before next read.
                  q.offer(Bytes(Chunk.array(buf))) >> F.delay(unsafeReplaceBuffer()) >> loopIfReady
                case len if len >= 0 =>
                  // Got a partial chunk.  Copy it, and reuse the current buffer.
                  q.offer(Bytes(Chunk.array(Arrays.copyOf(buf, len)))) >> loopIfReady
                case _ =>
                  F.unit
              }

            unsafeRunAndForget(go)
          }

          def onAllDataRead(): Unit =
            unsafeRunAndForget(q.offer(End))

          def onError(t: Throwable): Unit =
            unsafeRunAndForget(q.offer(Error(t)))

          def unsafeRunAndForget[A](fa: F[A]): Unit =
            dispatcher.unsafeRunAndForget(
              fa.onError { case t => F.delay(logger.error(t)("Error in servlet read listener")) }
            )
        })))

        def pullBody: Pull[F, Byte, Unit] =
          Pull.eval(q.take).flatMap {
            case Bytes(chunk) => Pull.output(chunk) >> pullBody
            case End => Pull.done
            case Error(t) => Pull.raiseError[F](t)
          }

        pullBody.stream.concurrently(readBody)
      }
    }
  }

  /* The queue implementation is influenced by ideas in jetty4s
   * https://github.com/IndiscriminateCoding/jetty4s/blob/0.0.10/server/src/main/scala/jetty4s/server/HttpResourceHandler.scala
   */
  override def bodyWriter(
      servletResponse: HttpServletResponse,
      dispatcher: Dispatcher[F],
  )(response: Response[F]): F[Unit] = {
    sealed trait Write
    final case class Bytes(chunk: Chunk[Byte]) extends Write
    case object End extends Write
    case object Init extends Write

    val autoFlush = response.isChunked

    F.delay(servletResponse.getOutputStream).flatMap { out =>
      Queue.bounded[F, Write](4).flatMap { q =>
        Deferred[F, Either[Throwable, Unit]].flatMap { done =>
          val writeBody = F.delay(out.setWriteListener(new WriteListener {
            def onWritePossible(): Unit = {
              def loopIfReady = F.delay(out.isReady()).flatMap {
                case true => go
                case false => F.unit
              }

              def flush =
                if (autoFlush) {
                  F.delay(out.isReady()).flatMap {
                    case true => F.delay(out.flush()) >> loopIfReady
                    case false => F.unit
                  }
                } else
                  loopIfReady

              def go: F[Unit] =
                q.take.flatMap {
                  case Bytes(slice: Chunk.ArraySlice[_]) =>
                    F.delay(
                      out.write(slice.values.asInstanceOf[Array[Byte]], slice.offset, slice.length)
                    ) >> flush
                  case Bytes(chunk) =>
                    F.delay(out.write(chunk.toArray)) >> flush
                  case End =>
                    F.delay(out.flush()) >> done.complete(Either.unit).attempt.void
                  case Init =>
                    if (autoFlush) flush else go
                }

              unsafeRunAndForget(go)
            }
            def onError(t: Throwable): Unit =
              unsafeRunAndForget(done.complete(Left(t)))

            def unsafeRunAndForget[A](fa: F[A]): Unit =
              dispatcher.unsafeRunAndForget(
                fa.onError { case t => F.delay(logger.error(t)("Error in servlet write listener")) }
              )
          }))

          val writes = Stream.emit(Init) ++ response.body.chunks.map(Bytes(_)) ++ Stream.emit(End)

          Stream
            .eval(writeBody >> done.get.rethrow)
            .mergeHaltL(writes.foreach(q.offer))
            .compile
            .drain
        }
      }
    }
  }
}
