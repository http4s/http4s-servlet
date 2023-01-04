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

package com.example

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.servlet.syntax._

import javax.servlet.ServletContextEvent
import javax.servlet.ServletContextListener
import javax.servlet.annotation.WebListener

@WebListener
/** 1. To start from sbt: `examples/Jetty/start`
  * 2. Browse to http://localhost:8080/http4s/
  * 3. To stop: `examples/Jetty/stop`
  */
class Bootstrap extends ServletContextListener {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req if req.method == Method.GET =>
      IO.pure(Response(Status.Ok).withEntity("pong"))
  }

  @volatile private var shutdown: IO[Unit] = IO.unit

  override def contextInitialized(sce: ServletContextEvent): Unit = {
    Dispatcher
      .parallel[IO]
      .allocated
      .flatMap { case (dispatcher, shutdown) =>
        IO(this.shutdown = shutdown) *>
          IO(sce.getServletContext.mountRoutes("example", routes, dispatcher = dispatcher))
      }
      .unsafeRunSync()
    ()
  }

  override def contextDestroyed(sce: ServletContextEvent): Unit =
    shutdown.unsafeRunSync()
}
