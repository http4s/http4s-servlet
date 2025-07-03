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
import org.apache.catalina.startup.Tomcat

import java.nio.file.Files
import javax.servlet.Servlet

object TestTomcatServer {

  @annotation.tailrec
  def deleteRecursively(files: List[java.io.File]): Unit =
    files match {
      case Nil => ()
      case file :: rest =>
        if (file.isDirectory) {
          val innerFiles = file.listFiles()
          if (innerFiles.isEmpty) {
            file.delete()
            deleteRecursively(rest)
          } else {
            deleteRecursively(innerFiles.toList ++ (file :: rest))
          }
        } else {
          file.delete()
          deleteRecursively(rest)
        }
    }

  def apply(
      servlet: Servlet,
      contextPath: String = "",
      servletPath: String = "/*",
  ): Resource[IO, Int /* port */ ] =
    Resource
      .make(IO {
        val tempDir = Files.createTempDirectory("tomcat-test")
        val server = new Tomcat()
        (server, tempDir)
      }) { case (server, tempDir) =>
        IO.pure((server, tempDir))
          .bracket { case (server, _) =>
            IO {
              server.stop()
              server.destroy()
            }
          } { case (_, tempDir) =>
            IO(deleteRecursively(List(tempDir.toFile))).void
          }
      }
      .evalMap { case (server, tempDir) =>
        IO {
          server.setPort(0)
          server.setBaseDir(tempDir.toFile.getCanonicalPath)
          val ctx = server.addContext(contextPath, null)
          val servletName = "TestServlet"
          val wrapper = Tomcat.addServlet(ctx, servletName, servlet)
          wrapper.setAsyncSupported(true)
          ctx.addServletMappingDecoded(servletPath, servletName)
          server.start()
          server.getConnector.getLocalPort
        }
      }

}
