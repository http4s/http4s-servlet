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

import cats.effect.IO
import cats.effect.Resource
import cats.effect.Timer
import cats.syntax.all._
import org.http4s.dsl.io._
import org.http4s.server.DefaultServiceErrorHandler
import org.http4s.syntax.all._
import org.http4s.testing.AutoCloseableResource

import java.net.URL
import scala.concurrent.duration._
import scala.io.Source

class AsyncHttp4sServletSuite extends Http4sSuite {
  private lazy val service = HttpRoutes
    .of[IO] {
      case GET -> Root / "simple" =>
        Ok("simple")
      case req @ POST -> Root / "echo" =>
        Ok(req.body)
      case GET -> Root / "shifted" =>
        IO.shift(munitExecutionContext) *>
          // Wait for a bit to make sure we lose the race
          Timer[IO].sleep(50.millis) *>
          Ok("shifted")
    }
    .orNotFound

  private val servletServer = ResourceFixture[Int](TestTomcatServer(servlet))

  private def get(serverPort: Int, path: String): IO[String] =
    testBlocker.delay[IO, String](
      AutoCloseableResource.resource(
        Source
          .fromURL(new URL(s"http://127.0.0.1:$serverPort/$path"))
      )(_.getLines().mkString)
    )

  import org.asynchttpclient.Dsl._

  private def post(serverPort: Int, path: String, contents: List[String]): IO[List[String]] =
    Resource.make(IO(asyncHttpClient()))(c => IO(c.close())).use { client =>
      contents
        .parTraverse { body =>
          IO.async { cb =>
            client
              .preparePost(s"http://127.0.0.1:$serverPort/$path")
              .setBody(body)
              .execute()
              .toCompletableFuture()
              .handle[Unit] {
                case (response, null) => cb(Right(response.getResponseBody()))
                case (_, t) => cb(Left(t))
              }
            ()
          }
        }
    }

  servletServer.test("AsyncHttp4sServlet handle GET requests") { server =>
    get(server, "simple").assertEquals("simple")
  }

  servletServer.test("AsyncHttp4sServlet handle POST requests") { server =>
    val alphabets = (('A' to 'Z') ++ ('a' to 'z')).toList
    val alphabetsLength = alphabets.length
    val contents = (1 to 14).map { i =>
      val number =
        scala.math.pow(2, i.toDouble).toInt - 1 // -1 for the end-of-line to make awk play nice
      s"$i $number ${(1 to number).map(_ => alphabets(scala.util.Random.nextInt(alphabetsLength))).mkString}\n"
    }.toList

    post(server, "echo", contents).assertEquals(contents)
  }

  servletServer.test("AsyncHttp4sServlet work for shifted IO") { server =>
    get(server, "shifted").assertEquals("shifted")
  }

  private lazy val servlet = new AsyncHttp4sServlet[IO](
    service = service,
    servletIo = NonBlockingServletIo[IO](4096),
    serviceErrorHandler = DefaultServiceErrorHandler[IO],
  )

}
