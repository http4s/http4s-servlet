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
import org.http4s.internal.bug
import org.log4s.getLogger

import java.util.Arrays
import java.util.concurrent.atomic.AtomicReference
import javax.servlet.ReadListener
import javax.servlet.WriteListener
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import scala.annotation.nowarn
import scala.annotation.tailrec

/** Determines the mode of I/O used for reading request bodies and writing response bodies.
  */
sealed abstract class ServletIo[F[_]: Async] {
  protected[servlet] val F: Async[F] = Async[F]

  @deprecated("Prefer requestBody, which has access to a Dispatcher", "0.23.12")
  protected[servlet] def reader(servletRequest: HttpServletRequest): EntityBody[F]

  @nowarn("cat=deprecation")
  def requestBody(
      servletRequest: HttpServletRequest,
      dispatcher: Dispatcher[F],
  ): Stream[F, Byte] = {
    val _ = dispatcher // unused
    reader(servletRequest)
  }

  /** May install a listener on the servlet response. */
  @deprecated("Prefer bodyWriter, which has access to a Dispatcher", "0.23.12")
  protected[servlet] def initWriter(servletResponse: HttpServletResponse): BodyWriter[F]

  @nowarn("cat=deprecation")
  def bodyWriter(servletResponse: HttpServletResponse, dispatcher: Dispatcher[F])(
      response: Response[F]
  ): F[Unit] = {
    val _ = dispatcher
    initWriter(servletResponse)(response)
  }
}

/** Use standard blocking reads and writes.
  *
  * This is more CPU efficient per request than [[NonBlockingServletIo]], but is likely to
  * require a larger request thread pool for the same load.
  */
final case class BlockingServletIo[F[_]: Async](chunkSize: Int) extends ServletIo[F] {
  override protected[servlet] def reader(servletRequest: HttpServletRequest): EntityBody[F] =
    io.readInputStream[F](F.pure(servletRequest.getInputStream), chunkSize)

  override protected[servlet] def initWriter(
      servletResponse: HttpServletResponse
  ): BodyWriter[F] = { (response: Response[F]) =>
    val out = servletResponse.getOutputStream
    val flush = response.isChunked
    response.body.chunks
      .evalTap { chunk =>
        Async[F].blocking {
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
final case class NonBlockingServletIo[F[_]: Async](chunkSize: Int) extends ServletIo[F] {
  private[this] val logger = getLogger

  private[this] def rightSome[A](a: A) = Right(Some(a))
  private[this] val rightNone = Right(None)

  override protected[servlet] def reader(servletRequest: HttpServletRequest): EntityBody[F] =
    Stream.suspend {
      sealed trait State
      case object Init extends State
      case object Ready extends State
      case object Complete extends State
      sealed case class Errored(t: Throwable) extends State
      sealed case class Blocked(cb: Callback[Option[Chunk[Byte]]]) extends State

      val in = servletRequest.getInputStream

      val state = new AtomicReference[State](Init)

      def read(cb: Callback[Option[Chunk[Byte]]]): Unit = {
        val buf = new Array[Byte](chunkSize)
        val len = in.read(buf)

        if (len == chunkSize) cb(rightSome(Chunk.array(buf)))
        else if (len < 0) {
          state.compareAndSet(Ready, Complete) // will not overwrite an `Errored` state
          cb(rightNone)
        } else if (len == 0) {
          logger.warn("Encountered a read of length 0")
          cb(rightSome(Chunk.empty))
        } else cb(rightSome(Chunk.array(buf, 0, len)))
      }

      if (in.isFinished) Stream.empty
      else {
        // This effect sets the callback and waits for the first bytes to read
        val registerRead =
          // Shift execution to a different EC
          F.async_[Option[Chunk[Byte]]] { cb =>
            if (!state.compareAndSet(Init, Blocked(cb)))
              cb(Left(bug("Shouldn't have gotten here: I should be the first to set a state")))
            else
              in.setReadListener(
                new ReadListener {
                  override def onDataAvailable(): Unit =
                    state.getAndSet(Ready) match {
                      case Blocked(cb) => read(cb)
                      case _ => ()
                    }

                  override def onError(t: Throwable): Unit =
                    state.getAndSet(Errored(t)) match {
                      case Blocked(cb) => cb(Left(t))
                      case _ => ()
                    }

                  override def onAllDataRead(): Unit =
                    state.getAndSet(Complete) match {
                      case Blocked(cb) => cb(rightNone)
                      case _ => ()
                    }
                }
              )
          }

        val readStream = Stream.eval(registerRead) ++ Stream
          .repeatEval( // perform the initial set then transition into normal read mode
            // Shift execution to a different EC
            F.async_[Option[Chunk[Byte]]] { cb =>
              @tailrec
              def go(): Unit =
                state.get match {
                  case Ready if in.isReady => read(cb)

                  case Ready => // wasn't ready so set the callback and double check that we're still not ready
                    val blocked = Blocked(cb)
                    if (state.compareAndSet(Ready, blocked))
                      if (in.isReady && state.compareAndSet(blocked, Ready))
                        read(cb) // data became available while we were setting up the callbacks
                      else {
                        /* NOOP: our callback is either still needed or has been handled */
                      }
                    else go() // Our state transitioned so try again.

                  case Complete => cb(rightNone)

                  case Errored(t) => cb(Left(t))

                  // This should never happen so throw a huge fit if it does.
                  case Blocked(c1) =>
                    val t = bug("Two callbacks found in read state")
                    cb(Left(t))
                    c1(Left(t))
                    logger.error(t)("This should never happen. Please report.")
                    throw t

                  case Init =>
                    cb(Left(bug("Should have left Init state by now")))
                }
              go()
            }
          )
        readStream.unNoneTerminate.flatMap(Stream.chunk)
      }
    }

  /* The queue implementation is influenced by ideas in jetty4s
   * https://github.com/IndiscriminateCoding/jetty4s/blob/0.0.10/server/src/main/scala/jetty4s/server/HttpResourceHandler.scala
   */
  override def requestBody(
      servletRequest: HttpServletRequest,
      dispatcher: Dispatcher[F],
  ): Stream[F, Byte] = {
    case object End

    Stream.eval(F.delay(servletRequest.getInputStream)).flatMap { in =>
      Stream.eval(Queue.bounded[F, Any](4)).flatMap { q =>
        val readBody = Stream.eval(F.delay(in.setReadListener(new ReadListener {
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
                  q.offer(Chunk.array(buf)) >> F.delay(unsafeReplaceBuffer()) >> loopIfReady
                case len if len > 0 =>
                  // Got a partial chunk.  Copy it, and reuse the current buffer.
                  q.offer(Chunk.array(Arrays.copyOf(buf, len))) >> loopIfReady
                case len if len == 0 => loopIfReady
                case _ =>
                  F.unit
              }

            unsafeRunAndForget(go)
          }

          def onAllDataRead(): Unit =
            unsafeRunAndForget(q.offer(End))

          def onError(t: Throwable): Unit =
            unsafeRunAndForget(q.offer(t))

          def unsafeRunAndForget[A](fa: F[A]): Unit =
            dispatcher.unsafeRunAndForget(
              fa.onError { case t => F.delay(logger.error(t)("Error in servlet read listener")) }
            )
        })))

        def pullBody: Pull[F, Byte, Unit] =
          Pull.eval(q.take).flatMap {
            case chunk: Chunk[Byte] @ unchecked => Pull.output(chunk) >> pullBody
            case End => Pull.done
            case t: Throwable => Pull.raiseError[F](t)
          }

        readBody.flatMap(_ => pullBody.stream)
      }
    }
  }

  override protected[servlet] def initWriter(
      servletResponse: HttpServletResponse
  ): BodyWriter[F] = {
    sealed trait State
    case object Init extends State
    case object Ready extends State
    sealed case class Errored(t: Throwable) extends State
    sealed case class Blocked(cb: Callback[Chunk[Byte] => Unit]) extends State
    sealed case class AwaitingLastWrite(cb: Callback[Unit]) extends State

    val out = servletResponse.getOutputStream
    /*
     * If onWritePossible isn't called at least once, Tomcat begins to throw
     * NullPointerExceptions from NioEndpoint$SocketProcessor.doRun under
     * load.  The Init state means we block callbacks until the WriteListener
     * fires.
     */
    val state = new AtomicReference[State](Init)
    @volatile var autoFlush = false

    val writeChunk = Right { (chunk: Chunk[Byte]) =>
      if (!out.isReady)
        logger.error(s"writeChunk called while out was not ready, bytes will be lost!")
      else {
        out.write(chunk.toArray)
        if (autoFlush && out.isReady)
          out.flush()
      }
    }

    val listener = new WriteListener {
      override def onWritePossible(): Unit =
        state.getAndSet(Ready) match {
          case Blocked(cb) => cb(writeChunk)
          case AwaitingLastWrite(cb) => cb(Right(()))
          case old @ _ => ()
        }

      override def onError(t: Throwable): Unit =
        state.getAndSet(Errored(t)) match {
          case Blocked(cb) => cb(Left(t))
          case AwaitingLastWrite(cb) => cb(Left(t))
          case _ => ()
        }
    }
    /*
     * This must be set on the container thread in Tomcat, or onWritePossible
     * will not be invoked.  This side effect needs to run between the acquisition
     * of the servletResponse and the calculation of the http4s Response.
     */
    out.setWriteListener(listener)

    val awaitLastWrite = Stream.exec {
      // Shift execution to a different EC
      F.async_[Unit] { cb =>
        state.getAndSet(AwaitingLastWrite(cb)) match {
          case Ready if out.isReady => cb(Right(()))
          case _ => ()
        }
      }
    }

    val chunkHandler =
      F.async_[Chunk[Byte] => Unit] { cb =>
        val blocked = Blocked(cb)
        state.getAndSet(blocked) match {
          case Ready if out.isReady =>
            if (state.compareAndSet(blocked, Ready))
              cb(writeChunk)
          case e @ Errored(t) =>
            if (state.compareAndSet(blocked, e))
              cb(Left(t))
          case _ =>
            ()
        }
      }

    def flushPrelude =
      if (autoFlush)
        chunkHandler.map(_(Chunk.empty[Byte]))
      else
        F.unit

    { (response: Response[F]) =>
      if (response.isChunked)
        autoFlush = true
      flushPrelude *>
        response.body.chunks
          .evalMap(chunk => chunkHandler.map(_(chunk)))
          .append(awaitLastWrite)
          .compile
          .drain
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
