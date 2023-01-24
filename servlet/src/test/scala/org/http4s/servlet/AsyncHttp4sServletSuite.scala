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
import cats.syntax.all._
import fs2.Chunk
import fs2.Stream
import munit.CatsEffectSuite
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.api.{Response => JResponse}
import org.eclipse.jetty.client.util.BytesContentProvider
import org.eclipse.jetty.client.util.DeferredContentProvider
import org.http4s.dsl.io._
import org.http4s.syntax.all._

import java.nio.ByteBuffer
import scala.concurrent.duration._

class AsyncHttp4sServletSuite extends CatsEffectSuite {
  private val clientR = Resource.make(IO {
    val client = new HttpClient()
    client.start()
    client
  })(client => IO(client.stop()))

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
    ResourceFixture[Int](
      Dispatcher.parallel[IO].flatMap(d => TestEclipseServer(servlet(d, asyncTimeout)))
    )

  private def get(client: HttpClient, serverPort: Int, path: String): IO[String] =
    IO.blocking(
      client.GET(s"http://127.0.0.1:$serverPort/$path")
    ).map(_.getContentAsString)

  servletServer().test("AsyncHttp4sServlet handle GET requests") { server =>
    clientR.use(get(_, server, "simple")).assertEquals("simple")
  }

  // We should handle an empty body
  servletServer().test("AsyncHttp4sServlet handle empty POST") { server =>
    clientR
      .use { client =>
        IO.blocking(
          client
            .POST(s"http://127.0.0.1:$server/echo")
            .send()
        ).map(resp => Chunk.array(resp.getContent))
      }
      .assertEquals(Chunk.empty)
  }

  // We should handle a regular, big body
  servletServer().test("AsyncHttp4sServlet handle multiple chunks upfront") { server =>
    val bytes = Stream.range(0, DefaultChunkSize * 2).map(_.toByte).to(Array)
    clientR
      .use { client =>
        IO.blocking(
          client
            .POST(s"http://127.0.0.1:$server/echo")
            .content(new BytesContentProvider(bytes))
            .send()
        ).map(resp => Chunk.array(resp.getContent))
      }
      .assertEquals(Chunk.array(bytes))
  }

  // We should be able to wake up if we're initially blocked
  servletServer().test("AsyncHttp4sServlet handle single-chunk, deferred POST") { server =>
    val bytes = Stream.range(0, DefaultChunkSize).map(_.toByte).to(Array)
    clientR
      .use { client =>
        for {
          content <- IO(new DeferredContentProvider())
          bodyFiber <- IO
            .async_[Chunk[Byte]] { cb =>
              var body = Chunk.empty[Byte]
              client
                .POST(s"http://127.0.0.1:$server/echo")
                .content(content)
                .send(new JResponse.Listener {
                  override def onContent(resp: JResponse, bb: ByteBuffer) = {
                    val buf = new Array[Byte](bb.remaining())
                    bb.get(buf)
                    body ++= Chunk.array(buf)
                  }
                  override def onFailure(resp: JResponse, t: Throwable) =
                    cb(Left(t))
                  override def onSuccess(resp: JResponse) =
                    cb(Right(body))
                })
            }
            .start
          _ <- IO(content.offer(ByteBuffer.wrap(bytes)))
          _ <- IO(content.close())
          body <- bodyFiber.joinWithNever
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
            content <- IO(new DeferredContentProvider())
            firstChunkReceived <- Deferred[IO, Unit]
            bodyFiber <- IO
              .async_[Chunk[Byte]] { cb =>
                var body = Chunk.empty[Byte]
                client
                  .POST(s"http://127.0.0.1:$server/echo")
                  .content(content)
                  .send(new JResponse.Listener {
                    override def onContent(resp: JResponse, bb: ByteBuffer) =
                      dispatcher.unsafeRunSync(for {
                        _ <- firstChunkReceived.complete(()).attempt
                        buf <- IO(new Array[Byte](bb.remaining()))
                        _ <- IO(bb.get(buf))
                        _ <- IO { body = body ++ Chunk.array(buf) }
                      } yield ())
                    override def onFailure(resp: JResponse, t: Throwable) =
                      cb(Left(t))
                    override def onSuccess(resp: JResponse) =
                      cb(Right(body))
                  })
              }
              .start
            _ <- IO(content.offer(ByteBuffer.wrap(bytes)))
            _ <- firstChunkReceived.get
            _ <- IO(content.offer(ByteBuffer.wrap(bytes)))
            _ <- IO(content.close())
            body <- bodyFiber.joinWithNever
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
            content <- IO(new DeferredContentProvider())
            firstChunkReceived <- Deferred[IO, Unit]
            bodyFiber <- IO
              .async_[Chunk[Byte]] { cb =>
                var body = Chunk.empty[Byte]
                client
                  .POST(s"http://127.0.0.1:$server/echo")
                  .content(content)
                  .send(new JResponse.Listener {
                    override def onContent(resp: JResponse, bb: ByteBuffer) =
                      dispatcher.unsafeRunSync(for {
                        _ <- firstChunkReceived.complete(()).attempt
                        buf <- IO(new Array[Byte](bb.remaining()))
                        _ <- IO(bb.get(buf))
                        _ <- IO { body = body ++ Chunk.array(buf) }
                      } yield ())
                    override def onFailure(resp: JResponse, t: Throwable) =
                      cb(Left(t))
                    override def onSuccess(resp: JResponse) =
                      cb(Right(body))
                  })
              }
              .start
            _ <- IO(content.offer(ByteBuffer.wrap(Array[Byte](0.toByte))))
            _ <- firstChunkReceived.get
            _ <- IO(content.offer(ByteBuffer.wrap(Array[Byte](1.toByte))))
            _ <- IO(content.close())
            body <- bodyFiber.joinWithNever
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
        .use { dispatcher =>
          clientR.use { client =>
            for {
              content <- IO(new DeferredContentProvider())
              bodyFiber <- IO
                .async_[Chunk[Byte]] { cb =>
                  var body = Chunk.empty[Byte]
                  client
                    .POST(s"http://127.0.0.1:$server/echo")
                    .content(content)
                    .send(new JResponse.Listener {
                      override def onContent(resp: JResponse, bb: ByteBuffer) =
                        dispatcher.unsafeRunSync(for {
                          buf <- IO(new Array[Byte](bb.remaining()))
                          _ <- IO(bb.get(buf))
                          _ <- IO { body = body ++ Chunk.array(buf) }
                        } yield ())
                      override def onFailure(resp: JResponse, t: Throwable) =
                        cb(Left(t))
                      override def onSuccess(resp: JResponse) =
                        cb(Right(body))
                    })
                }
                .start
              _ <- body.toList.traverse_(b =>
                IO(content.offer(ByteBuffer.wrap(Array[Byte](b)))) >> IO(content.flush())
              )
              _ <- IO(content.close())
              body <- bodyFiber.joinWithNever
            } yield body
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
