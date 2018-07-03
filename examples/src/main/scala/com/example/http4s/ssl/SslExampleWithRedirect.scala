package com.example.http4s
package ssl

import cats.effect.{ConcurrentEffect, Timer}
import cats.syntax.option._
import fs2.StreamApp.ExitCode
import fs2._
import java.nio.file.Paths
import org.http4s.HttpRoutes
import org.http4s.Uri.{Authority, RegName, Scheme}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Host, Location}
import org.http4s.server.SSLKeyStoreSupport.StoreInfo
import org.http4s.server.{SSLKeyStoreSupport, ServerBuilder}
import scala.concurrent.ExecutionContext

abstract class SslExampleWithRedirect[F[_]: ConcurrentEffect]
    extends StreamApp[F]
    with Http4sDsl[F] {
  val securePort = 8443

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  // TODO: Reference server.jks from something other than one child down.
  val keypath: String = Paths.get("../server.jks").toAbsolutePath.toString

  def builder: ServerBuilder[F] with SSLKeyStoreSupport[F]

  val redirectService: HttpRoutes[F] = HttpRoutes.of[F] {
    case request =>
      request.headers.get(Host) match {
        case Some(Host(host, _)) =>
          val baseUri = request.uri.copy(
            scheme = Scheme.https.some,
            authority = Some(
              Authority(
                request.uri.authority.flatMap(_.userInfo),
                RegName(host),
                port = securePort.some)))
          MovedPermanently(Location(baseUri.withPath(request.uri.path)))
        case _ =>
          BadRequest()
      }
  }

  def sslStream(implicit timer: Timer[F]): Stream[F, ExitCode] =
    builder
      .withSSL(StoreInfo(keypath, "password"), keyManagerPassword = "secure")
      .mountService(new ExampleService[F].service, "/http4s")
      .bindHttp(8443)
      .serve

  def redirectStream: Stream[F, ExitCode] =
    builder
      .mountService(redirectService, "/http4s")
      .bindHttp(8080)
      .serve

  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] = {
    implicit val timer = Timer.derive[F]
    sslStream.mergeHaltBoth(redirectStream)
  }
}
