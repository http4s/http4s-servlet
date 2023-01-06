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

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.effect.std.Dispatcher
import cats.syntax.all._
import org.http4s.server._

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import scala.annotation.nowarn

class BlockingHttp4sServlet[F[_]] private (
    service: HttpApp[F],
    servletIo: ServletIo[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    dispatcher: Dispatcher[F],
)(implicit F: Sync[F])
    extends Http4sServlet[F](service, servletIo, dispatcher) {

  @deprecated("Use BlockingHttp4sServlet.builder", "0.23.13")
  def this(
      service: HttpApp[F],
      servletIo: BlockingServletIo[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      dispatcher: Dispatcher[F],
  )(implicit F: Sync[F]) =
    this(service, servletIo: ServletIo[F], serviceErrorHandler, dispatcher)(F)

  @deprecated("Binary compatibility", "0.23.12")
  private[servlet] def this(
      service: HttpApp[F],
      servletIo: ServletIo[F],
      serviceErrorHandler: ServiceErrorHandler[F],
      dispatcher: Dispatcher[F],
      async: Async[F],
  ) =
    this(service, servletIo, serviceErrorHandler, dispatcher)(async: Sync[F])

  override def service(
      servletRequest: HttpServletRequest,
      servletResponse: HttpServletResponse,
  ): Unit = {
    val result = F
      .defer {
        val bodyWriter = servletIo.bodyWriter(servletResponse, dispatcher) _

        val render = toRequest(servletRequest).fold(
          onParseFailure(_, servletResponse, bodyWriter),
          handleRequest(_, servletResponse, bodyWriter),
        )

        render
      }
      .handleErrorWith(errorHandler(servletResponse))
    dispatcher.unsafeRunSync(result)
  }

  private def handleRequest(
      request: Request[F],
      servletResponse: HttpServletResponse,
      bodyWriter: BodyWriter[F],
  ): F[Unit] =
    // Note: We're catching silly user errors in the lift => flatten.
    F.defer(serviceFn(request))
      .recoverWith(serviceErrorHandler(request))
      .flatMap(renderResponse(_, servletResponse, bodyWriter))

  private def errorHandler(servletResponse: HttpServletResponse)(t: Throwable): F[Unit] =
    F.defer {
      if (servletResponse.isCommitted) {
        logger.error(t)("Error processing request after response was committed")
        F.unit
      } else {
        logger.error(t)("Error processing request")
        val response = Response[F](Status.InternalServerError)
        // We don't know what I/O mode we're in here, and we're not rendering a body
        // anyway, so we use a NullBodyWriter.
        renderResponse(response, servletResponse, nullBodyWriter)
      }
    }
}

object BlockingHttp4sServlet {

  class Builder[F[_]] private[BlockingHttp4sServlet] (
      httpApp: HttpApp[F],
      dispatcher: Dispatcher[F],
      chunkSize: Option[Int],
  ) {
    private def copy(
        httpApp: HttpApp[F] = httpApp,
        dispatcher: Dispatcher[F] = dispatcher,
        chunkSize: Option[Int] = chunkSize,
    ): Builder[F] =
      new Builder[F](
        httpApp,
        dispatcher,
        chunkSize,
      ) {}

    @nowarn("cat=deprecation")
    def build(implicit F: Async[F]): BlockingHttp4sServlet[F] =
      new BlockingHttp4sServlet(
        httpApp,
        BlockingServletIo(chunkSize.getOrElse(DefaultChunkSize)),
        DefaultServiceErrorHandler,
        dispatcher,
      )

    def withHttpApp(httpApp: HttpApp[F]): Builder[F] =
      copy(httpApp = httpApp)

    def withDispatcher(dispatcher: Dispatcher[F]): Builder[F] =
      copy(dispatcher = dispatcher)

    def withChunkSize(chunkSize: Int): Builder[F] =
      copy(chunkSize = Some(chunkSize))
  }

  def builder[F[_]](httpApp: HttpApp[F], dispatcher: Dispatcher[F]): Builder[F] =
    new Builder[F](httpApp, dispatcher, None) {}

  @deprecated(
    "Use `builder` instead",
    "0.23.13",
  )
  def apply[F[_]](
      service: HttpApp[F],
      servletIo: ServletIo[F],
      dispatcher: Dispatcher[F],
  )(implicit F: Sync[F]): BlockingHttp4sServlet[F] =
    new BlockingHttp4sServlet(
      service,
      servletIo,
      DefaultServiceErrorHandler,
      dispatcher,
    )

  @deprecated(
    "Use `builder` instead",
    "0.23.12",
  )
  def apply[F[_]](
      service: HttpApp[F],
      servletIo: ServletIo[F],
      dispatcher: Dispatcher[F],
      async: Async[F],
  ): BlockingHttp4sServlet[F] =
    new BlockingHttp4sServlet(
      service,
      servletIo,
      DefaultServiceErrorHandler(async),
      dispatcher,
      async,
    )
}
