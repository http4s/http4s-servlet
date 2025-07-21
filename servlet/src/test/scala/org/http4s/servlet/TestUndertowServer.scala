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

package org.http4s.servlet

import cats.effect.IO
import cats.effect.Resource
import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.PathHandler
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.DeploymentManager
import io.undertow.servlet.api.ServletInfo
import io.undertow.servlet.util.ImmediateInstanceFactory

import javax.servlet.Servlet

object TestUndertowServer {

  def apply(
      servlet: Servlet,
      contextPath: String = "/",
      servletPath: String = "/*",
  ): Resource[IO, Int /* port */ ] =
    Resource
      .make(IO {
        /* Create deployment info - use ServletInfo constructor that accepts instance factory */
        val servletInfo = new ServletInfo(
          "http4s-servlet",
          classOf[Servlet],
          new ImmediateInstanceFactory[Servlet](servlet),
        )
        servletInfo.addMapping(servletPath)
        servletInfo.setAsyncSupported(true)

        val deploymentInfo = Servlets.deployment()
        deploymentInfo.setClassLoader(TestUndertowServer.getClass.getClassLoader)
        deploymentInfo.setContextPath(contextPath)
        deploymentInfo.setDeploymentName("http4s-servlet-test")
        deploymentInfo.addServlet(servletInfo)

        /* Create deployment manager */
        val manager: DeploymentManager = Servlets.defaultContainer().addDeployment(deploymentInfo)
        manager.deploy()

        val servletHandler = manager.start()

        /* Create path handler */
        val pathHandler: HttpHandler =
          if (contextPath == "/") servletHandler
          else new PathHandler().addPrefixPath(contextPath, servletHandler)

        /* Create and start Undertow server */
        val server = Undertow
          .builder()
          .addHttpListener(0, "localhost") // Port 0 = random available port
          .setHandler(pathHandler)
          .build()

        server.start()

        /* Get the assigned port */
        val port = server.getListenerInfo
          .get(0)
          .getAddress
          .asInstanceOf[java.net.InetSocketAddress]
          .getPort

        (server, manager, port)
      }) { case (server, manager, _) =>
        IO {
          server.stop()
          manager.stop()
          manager.undeploy()
        }
      }
      .map { case (_, _, port) => port }
}
