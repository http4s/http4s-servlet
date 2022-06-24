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
import cats.effect.kernel.Deferred
import cats.effect.std.Dispatcher
import cats.syntax.all._
import org.http4s.server._

import javax.servlet._
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import scala.concurrent.duration.Duration

class AsyncHttp4sServlet[F[_]] private[servlet] (
    httpApp: HttpApp[F],
    asyncTimeout: Duration,
    servletIo: ServletIo[F],
    serviceErrorHandler: ServiceErrorHandler[F],
    dispatcher: Dispatcher[F],
)(implicit F: Async[F])
    extends Http4sServlet[F](httpApp, servletIo, dispatcher) {
  private val asyncTimeoutMillis =
    if (asyncTimeout.isFinite) asyncTimeout.toMillis else -1 // -1 == Inf

  override def init(config: ServletConfig): Unit = {
    super.init(config)
    logServletIo()
  }

  private def logServletIo(): Unit =
    logger.info(servletIo match {
      case BlockingServletIo(chunkSize) =>
        s"Using blocking servlet I/O with chunk size $chunkSize"
      case NonBlockingServletIo(chunkSize) =>
        s"Using non-blocking servlet I/O with chunk size $chunkSize"
    })

  override def service(
      servletRequest: HttpServletRequest,
      servletResponse: HttpServletResponse,
  ): Unit =
    try {
      val ctx = servletRequest.startAsync()
      ctx.setTimeout(asyncTimeoutMillis)
      // Must be done on the container thread for Tomcat's sake when using async I/O.
      val bodyWriter = servletIo.bodyWriter(servletResponse, dispatcher) _
      val result = F
        .attempt(
          toRequest(servletRequest).fold(
            onParseFailure(_, servletResponse, bodyWriter),
            handleRequest(ctx, _, bodyWriter),
          )
        )
        .flatMap {
          case Right(()) => F.delay(ctx.complete)
          case Left(t) => errorHandler(servletRequest, servletResponse)(t)
        }
      dispatcher.unsafeRunAndForget(result)
    } catch errorHandler(servletRequest, servletResponse).andThen(dispatcher.unsafeRunSync _)

  private def handleRequest(
      ctx: AsyncContext,
      request: Request[F],
      bodyWriter: BodyWriter[F],
  ): F[Unit] =
    Deferred[F, Unit].flatMap { gate =>
      // It is an error to add a listener to an async context that is
      // already completed, so we must take care to add the listener
      // before the response can complete.

      val timeout =
        F.async[Response[F]](cb =>
          gate.complete(ctx.addListener(new AsyncTimeoutHandler(cb))).as(Option.empty[F[Unit]])
        )
      val response =
        gate.get *>
          F.defer(serviceFn(request))
            .recoverWith(serviceErrorHandler(request))
      val servletResponse = ctx.getResponse.asInstanceOf[HttpServletResponse]
      F.race(timeout, response).flatMap(r => renderResponse(r.merge, servletResponse, bodyWriter))
    }

  private def errorHandler(
      servletRequest: ServletRequest,
      servletResponse: HttpServletResponse,
  ): PartialFunction[Throwable, F[Unit]] = {
    case t: Throwable if servletResponse.isCommitted =>
      F.delay(logger.error(t)("Error processing request after response was committed"))

    case t: Throwable =>
      val response = Response[F](Status.InternalServerError)
      // We don't know what I/O mode we're in here, and we're not rendering a body
      // anyway, so we use a NullBodyWriter.
      val f = renderResponse(response, servletResponse, nullBodyWriter) *>
        F.delay(
          if (servletRequest.isAsyncStarted)
            servletRequest.getAsyncContext.complete()
        )
      F.delay(logger.error(t)("Error processing request")) *> F
        .attempt(f)
        .flatMap {
          case Right(()) => F.unit
          case Left(e) => F.delay(logger.error(e)("Error in error handler"))
        }
  }

  private class AsyncTimeoutHandler(cb: Callback[Response[F]]) extends AbstractAsyncListener {
    override def onTimeout(event: AsyncEvent): Unit = {
      val req = event.getAsyncContext.getRequest.asInstanceOf[HttpServletRequest]
      logger.info(s"Request timed out: ${req.getMethod} ${req.getServletPath}${req.getPathInfo}")
      cb(Right(Response.timeout[F]))
    }
  }
}

object AsyncHttp4sServlet {

  /** A builder for an Http4sServlet.
    *
    * @tparam F the effect type of the servlet
    * @tparam S the state of the builder.  After [[withHttpApp]] and
    * [[withDispatcher]] are called, the state reaches
    * [[Builder.State.Complete]], and the servlet can be built.
    */
  class Builder[F[_], S <: Builder.State] private[AsyncHttp4sServlet] (
      httpApp: Option[HttpApp[F]],
      dispatcher: Option[Dispatcher[F]],
      asyncTimeout: Option[Duration],
      chunkSize: Option[Int],
  ) {
    import Builder.State._

    def copy[S2 <: S](
        httpApp: Option[HttpApp[F]] = httpApp,
        dispatcher: Option[Dispatcher[F]] = dispatcher,
        asyncTimeout: Option[Duration] = asyncTimeout,
        chunkSize: Option[Int] = chunkSize,
    ): Builder[F, S2] =
      new Builder[F, S2](
        httpApp,
        dispatcher,
        asyncTimeout,
        chunkSize,
      ) {}

    /** Builds the servlet.  Requires that [[withHttpApp]] and
      * [[withDispatcher]] were called first.
      */
    def build(implicit F: Async[F], ev: S =:= Complete): AsyncHttp4sServlet[F] =
      new AsyncHttp4sServlet(
        httpApp.get,
        asyncTimeout.getOrElse(Duration.Inf),
        NonBlockingServletIo(chunkSize.getOrElse(DefaultChunkSize)),
        DefaultServiceErrorHandler,
        dispatcher.get,
      )

    def withHttpApp(httpApp: HttpApp[F]): Builder[F, S with HasHttpApp] =
      copy(httpApp = Some(httpApp))

    def withDispatcher(dispatcher: Dispatcher[F]): Builder[F, S with HasDispatcher] =
      copy(dispatcher = Some(dispatcher))

    def withAsyncTimeout(asyncTimeout: Duration): Builder[F, S] =
      copy(asyncTimeout = Some(asyncTimeout))

    def withChunkSize(chunkSize: Int): Builder[F, S] =
      copy(chunkSize = Some(chunkSize))
  }

  object Builder {
    sealed trait State
    object State {
      sealed trait Init extends State
      sealed trait HasHttpApp extends State
      sealed trait HasDispatcher extends State
      type Complete = Init with HasHttpApp with HasDispatcher
    }
  }

  def builder[F[_]]: Builder[F, Builder.State.Init] =
    new Builder[F, Builder.State.Init](None, None, None, None) {}

  @deprecated("Use `builder`.  `service` is renamed to `httpApp`.", "0.22.13")
  def apply[F[_]: Async](
      service: HttpApp[F],
      asyncTimeout: Duration = Duration.Inf,
      dispatcher: Dispatcher[F],
  ): AsyncHttp4sServlet[F] =
    AsyncHttp4sServlet
      .builder[F]
      .withHttpApp(service)
      .withAsyncTimeout(asyncTimeout)
      .withDispatcher(dispatcher)
      .build
}
