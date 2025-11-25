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

import cats.Monoid
import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Dispatcher
import fs2.Chunk
import fs2.Stream
import munit.CatsEffectSuite
import org.asynchttpclient.AsyncHandler
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.Dsl._
import org.asynchttpclient.HttpResponseBodyPart
import org.asynchttpclient.HttpResponseStatus
import org.http4s.dsl.io._
import org.http4s.syntax.all._

import scala.concurrent.duration._

class AsyncHttp4sServletSuite extends CatsEffectSuite {
  private val clientR = Resource.make(IO(asyncHttpClient()))(client => IO(client.close()))

  private lazy val service = HttpRoutes
    .of[IO] {
      case GET -> Root / "simple" =>
        Ok("simple")
      case req @ POST -> Root / "echo" =>
        Ok(req.body)
      case GET -> Root / "shifted" =>
        // Wait for a bit to make sure we lose the race
        (IO.sleep(50.millis) *>
          Ok("shifted")).evalOn(munitExecutionContext)
      case GET -> Root / "never" =>
        IO.never
    }
    .orNotFound

  private def servletServer(asyncTimeout: FiniteDuration = 10.seconds) =
    ResourceFunFixture[Int](
      Dispatcher.parallel[IO].flatMap(d => TestUndertowServer(servlet(d, asyncTimeout)))
    )

  private def get(client: AsyncHttpClient, serverPort: Int, path: String): IO[String] =
    IO.fromCompletableFuture(IO.blocking {
      client
        .prepareGet(s"http://127.0.0.1:$serverPort/$path")
        .execute()
        .toCompletableFuture
        .thenApply[String](_.getResponseBody)
    })

  servletServer().test("AsyncHttp4sServlet handle GET requests") { server =>
    clientR.use(get(_, server, "simple")).assertEquals("simple")
  }

  // We should handle an empty body
  servletServer().test("AsyncHttp4sServlet handle empty POST") { server =>
    clientR
      .use { client =>
        IO.fromCompletableFuture(IO.blocking {
          client
            .preparePost(s"http://127.0.0.1:$server/echo")
            .execute()
            .toCompletableFuture
            .thenApply[Chunk[Byte]](resp => Chunk.array(resp.getResponseBodyAsBytes))
        })
      }
      .assertEquals(Chunk.empty[Byte])
  }

  // We should handle a regular, big body
  servletServer().test("AsyncHttp4sServlet handle multiple chunks upfront") { server =>
    val bytes = Stream.range(0, DefaultChunkSize * 2).map(_.toByte).to(Array)
    clientR
      .use { client =>
        IO.fromCompletableFuture(IO.blocking {
          client
            .preparePost(s"http://127.0.0.1:$server/echo")
            .setBody(bytes)
            .execute()
            .toCompletableFuture
            .thenApply[Chunk[Byte]](resp => Chunk.array(resp.getResponseBodyAsBytes))
        })
      }
      .assertEquals(Chunk.array(bytes))
  }

  // We should be able to wake up if we're initially blocked
  servletServer().test("AsyncHttp4sServlet handle single-chunk, deferred POST") { server =>
    val bytes = Stream.range(0, DefaultChunkSize).map(_.toByte).to(Array)
    clientR
      .use { client =>
        for {
          bodyRef <- IO.ref(Chunk.empty[Byte])
          _ <- IO.fromCompletableFuture(IO {
            val bodyCollector = new AsyncHandler[Unit] {
              override def onStatusReceived(
                  responseStatus: HttpResponseStatus
              ): AsyncHandler.State =
                AsyncHandler.State.CONTINUE

              override def onHeadersReceived(
                  headers: io.netty.handler.codec.http.HttpHeaders
              ): AsyncHandler.State =
                AsyncHandler.State.CONTINUE

              override def onBodyPartReceived(
                  bodyPart: HttpResponseBodyPart
              ): AsyncHandler.State = {
                val buf = bodyPart.getBodyByteBuffer
                val array = new Array[Byte](buf.remaining())
                buf.get(array)
                bodyRef.update(_ ++ Chunk.array(array)).unsafeRunSync()
                AsyncHandler.State.CONTINUE
              }

              override def onCompleted(): Unit = {}

              override def onThrowable(t: Throwable): Unit = {}
            }

            client
              .preparePost(s"http://127.0.0.1:$server/echo")
              .setBody(bytes)
              .execute(bodyCollector)
              .toCompletableFuture
          })
          body <- bodyRef.get
        } yield body
      }
      .assertEquals(Chunk.array(bytes))
  }

  // We should be able to wake up after being blocked
  servletServer().test("AsyncHttp4sServlet handle two-chunk, deferred POST") { server =>
    // Show that we can read, be blocked, and read again
    val bytes = Stream.range(0, DefaultChunkSize).map(_.toByte).to(Array)
    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
        clientR.use { client =>
          for {
            firstChunkReceived <- Deferred[IO, Unit]
            bodyRef <- IO.ref(Chunk.empty[Byte])
            _ <- IO.fromCompletableFuture(IO {
              val bodyCollector = new AsyncHandler[Unit] {
                private var firstChunk = true

                override def onStatusReceived(
                    responseStatus: HttpResponseStatus
                ): AsyncHandler.State =
                  AsyncHandler.State.CONTINUE

                override def onHeadersReceived(
                    headers: io.netty.handler.codec.http.HttpHeaders
                ): AsyncHandler.State =
                  AsyncHandler.State.CONTINUE

                override def onBodyPartReceived(
                    bodyPart: HttpResponseBodyPart
                ): AsyncHandler.State = dispatcher.unsafeRunSync(for {
                  _ <-
                    if (firstChunk) {
                      firstChunk = false
                      firstChunkReceived.complete(()).attempt
                    } else {
                      IO.unit
                    }
                  buf <- IO(bodyPart.getBodyByteBuffer)
                  array <- IO(new Array[Byte](buf.remaining()))
                  _ <- IO(buf.get(array))
                  _ <- bodyRef.update(_ ++ Chunk.array(array))
                } yield AsyncHandler.State.CONTINUE)

                override def onCompleted(): Unit = {}

                override def onThrowable(t: Throwable): Unit = {}
              }

              client
                .preparePost(s"http://127.0.0.1:$server/echo")
                .setBody(bytes ++ bytes)
                .execute(bodyCollector)
                .toCompletableFuture
            })
            _ <- firstChunkReceived.get
            body <- bodyRef.get
          } yield body
        }
      }
      .assertEquals(Monoid[Chunk[Byte]].combineN(Chunk.array(bytes), 2))
  }

  // We shouldn't block when we receive less than a chunk at a time
  servletServer().test("AsyncHttp4sServlet handle two itsy-bitsy deferred chunk POST") { server =>
    Dispatcher
      .parallel[IO]
      .use { dispatcher =>
        clientR.use { client =>
          for {
            firstChunkReceived <- Deferred[IO, Unit]
            bodyRef <- IO.ref(Chunk.empty[Byte])
            _ <- IO.fromCompletableFuture(IO {
              val bodyCollector = new AsyncHandler[Unit] {
                private var firstChunk = true

                override def onStatusReceived(
                    responseStatus: HttpResponseStatus
                ): AsyncHandler.State =
                  AsyncHandler.State.CONTINUE

                override def onHeadersReceived(
                    headers: io.netty.handler.codec.http.HttpHeaders
                ): AsyncHandler.State =
                  AsyncHandler.State.CONTINUE

                override def onBodyPartReceived(
                    bodyPart: HttpResponseBodyPart
                ): AsyncHandler.State = dispatcher.unsafeRunSync(for {
                  _ <-
                    if (firstChunk) {
                      firstChunk = false
                      firstChunkReceived.complete(()).attempt
                    } else {
                      IO.unit
                    }
                  buf <- IO(bodyPart.getBodyByteBuffer)
                  array <- IO(new Array[Byte](buf.remaining()))
                  _ <- IO(buf.get(array))
                  _ <- bodyRef.update(_ ++ Chunk.array(array))
                } yield AsyncHandler.State.CONTINUE)

                override def onCompleted(): Unit = {}

                override def onThrowable(t: Throwable): Unit = {}
              }

              client
                .preparePost(s"http://127.0.0.1:$server/echo")
                .setBody(Array[Byte](0.toByte, 1.toByte))
                .execute(bodyCollector)
                .toCompletableFuture
            })
            _ <- firstChunkReceived.get
            body <- bodyRef.get
          } yield body
        }
      }
      .assertEquals(Chunk(0.toByte, 1.toByte))
  }

  servletServer().test("AsyncHttp4sServlet should not reorder lots of itsy-bitsy chunks") {
    server =>
      val body = (0 until 4096).map(_.toByte).toArray
      Dispatcher
        .parallel[IO]
        .use { _ =>
          clientR.use { client =>
            for {
              bodyRef <- IO.ref(Chunk.empty[Byte])
              _ <- IO.fromCompletableFuture(IO {
                val bodyCollector = new AsyncHandler[Unit] {
                  override def onStatusReceived(
                      responseStatus: HttpResponseStatus
                  ): AsyncHandler.State =
                    AsyncHandler.State.CONTINUE

                  override def onHeadersReceived(
                      headers: io.netty.handler.codec.http.HttpHeaders
                  ): AsyncHandler.State =
                    AsyncHandler.State.CONTINUE

                  override def onBodyPartReceived(
                      bodyPart: HttpResponseBodyPart
                  ): AsyncHandler.State = {
                    val buf = bodyPart.getBodyByteBuffer
                    val array = new Array[Byte](buf.remaining())
                    buf.get(array)
                    bodyRef.update(_ ++ Chunk.array(array)).unsafeRunSync()
                    AsyncHandler.State.CONTINUE
                  }

                  override def onCompleted(): Unit = {}

                  override def onThrowable(t: Throwable): Unit = {}
                }

                client
                  .preparePost(s"http://127.0.0.1:$server/echo")
                  .setBody(body)
                  .execute(bodyCollector)
                  .toCompletableFuture
              })
              responseBody <- bodyRef.get
            } yield responseBody
          }
        }
        .assertEquals(Chunk.array(body))
  }

  servletServer().test("AsyncHttp4sServlet work for shifted IO") { server =>
    clientR.use(get(_, server, "shifted")).assertEquals("shifted")
  }

  servletServer(3.seconds).test("AsyncHttp4sServlet timeout fires") { server =>
    clientR.use(get(_, server, "never")).map(_.contains("Error 500 AsyncContext timeout"))
  }

  private def servlet(dispatcher: Dispatcher[IO], asyncTimeout: FiniteDuration) =
    AsyncHttp4sServlet
      .builder[IO](service, dispatcher)
      .withChunkSize(DefaultChunkSize)
      .withAsyncTimeout(asyncTimeout)
      .build
}
