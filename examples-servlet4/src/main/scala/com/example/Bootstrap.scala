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

import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.IO
import org.http4s._
import org.http4s.servlet.syntax._

import javax.servlet.ServletContextEvent
import javax.servlet.ServletContextListener
import javax.servlet.annotation.WebListener
import scala.concurrent.ExecutionContext

@WebListener
/** JettyPlugin from xsbt-web-plugin doesn't support Jetty 12.
  * sbt-war, the successor of xsbt-web-plugin, doesn't support Jetty at all.
  * 1. To start from sbt: `examplesServlet4/Jetty/start`
  * 2. Browse to http://localhost:8080/http4s/
  * 3. To stop: `examplesServlet4/Jetty/stop`
  */
class Bootstrap extends ServletContextListener {
  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
  implicit val concurrentEffect: ConcurrentEffect[IO] = IO.ioConcurrentEffect(contextShift)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req if req.method == Method.GET =>
      IO.pure(Response(Status.Ok).withEntity("pong"))
  }

  override def contextInitialized(sce: ServletContextEvent): Unit = {
    IO(sce.getServletContext.mountService("example", routes)).unsafeRunSync()
    ()
  }

  override def contextDestroyed(sce: ServletContextEvent): Unit = ()

}
