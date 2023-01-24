package org.http4s.servlet

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global

import org.openjdk.jmh.annotations._
import org.http4s.servlet.NonBlockingServletIo

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.servlet.{ServletInputStream, ReadListener}
import javax.servlet.http.HttpServletRequest
import scala.util.Random

/** To do comparative benchmarks between versions:
  *
  * benchmarks/run-benchmark AsyncBenchmark
  *
  * This will generate results in `benchmarks/results`.
  *
  * Or to run the benchmark from within sbt:
  *
  * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.AsyncBenchmark
  *
  * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
  * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
  * more is better.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ServletIoBenchmarks {

  @Param(Array("100000"))
  var size: Int = _

  @Param(Array("1000"))
  var iters: Int = _

  def servletRequest: HttpServletRequest = new HttpServletRequestStub(
    new TestServletInputStream(Random.nextBytes(size))
  )

  @Benchmark
  def reader() = {
    val req = servletRequest
    val servletIo = NonBlockingServletIo[IO](4096)

    def loop(i: Int): IO[Unit] =
      if (i == iters) IO.unit else servletIo.reader(req).compile.drain >> loop(i + 1)

    loop(0).unsafeRunSync()
  }

  @Benchmark
  def requestBody() = {
    val req = servletRequest
    val servletIo = NonBlockingServletIo[IO](4096)

    Dispatcher
      .sequential[IO]
      .use { disp =>
        def loop(i: Int): IO[Unit] =
          if (i == iters) IO.unit else servletIo.requestBody(req, disp).compile.drain >> loop(i + 1)

        loop(0)
      }
      .unsafeRunSync()
  }

  class TestServletInputStream(body: Array[Byte]) extends ServletInputStream {
    private var readListener: ReadListener = null
    private val in = new ByteArrayInputStream(body)

    override def isReady: Boolean = true

    override def isFinished: Boolean = in.available() == 0

    override def setReadListener(readListener: ReadListener): Unit = {
      this.readListener = readListener
      readListener.onDataAvailable()
    }

    override def read(): Int = {
      val result = in.read()
      if (in.available() == 0)
        readListener.onAllDataRead()
      result
    }
  }

  case class HttpServletRequestStub(
      inputStream: ServletInputStream
  ) extends HttpServletRequest {
    def getInputStream(): ServletInputStream = inputStream

    def authenticate(x$1: javax.servlet.http.HttpServletResponse): Boolean = ???
    def changeSessionId(): String = ???
    def getAuthType(): String = ???
    def getContextPath(): String = ???
    def getCookies(): Array[javax.servlet.http.Cookie] = ???
    def getDateHeader(x$1: String): Long = ???
    def getHeader(x$1: String): String = ???
    def getHeaderNames(): java.util.Enumeration[String] = ???
    def getHeaders(x$1: String): java.util.Enumeration[String] = ???
    def getIntHeader(x$1: String): Int = ???
    def getMethod(): String = ???
    def getPart(x$1: String): javax.servlet.http.Part = ???
    def getParts(): java.util.Collection[javax.servlet.http.Part] = ???
    def getPathInfo(): String = ???
    def getPathTranslated(): String = ???
    def getQueryString(): String = ???
    def getRemoteUser(): String = ???
    def getRequestURI(): String = ???
    def getRequestURL(): StringBuffer = ???
    def getRequestedSessionId(): String = ???
    def getServletPath(): String = ???
    def getSession(): javax.servlet.http.HttpSession = ???
    def getSession(x$1: Boolean): javax.servlet.http.HttpSession = ???
    def getUserPrincipal(): java.security.Principal = ???
    def isRequestedSessionIdFromCookie(): Boolean = ???
    def isRequestedSessionIdFromURL(): Boolean = ???
    def isRequestedSessionIdFromUrl(): Boolean = ???
    def isRequestedSessionIdValid(): Boolean = ???
    def isUserInRole(x$1: String): Boolean = ???
    def login(x$1: String, x$2: String): Unit = ???
    def logout(): Unit = ???
    def upgrade[T <: javax.servlet.http.HttpUpgradeHandler](x$1: Class[T]): T = ???
    def getAsyncContext(): javax.servlet.AsyncContext = ???
    def getAttribute(x$1: String): Object = ???
    def getAttributeNames(): java.util.Enumeration[String] = ???
    def getCharacterEncoding(): String = ???
    def getContentLength(): Int = ???
    def getContentLengthLong(): Long = ???
    def getContentType(): String = ???
    def getDispatcherType(): javax.servlet.DispatcherType = ???
    def getLocalAddr(): String = ???
    def getLocalName(): String = ???
    def getLocalPort(): Int = ???
    def getLocale(): java.util.Locale = ???
    def getLocales(): java.util.Enumeration[java.util.Locale] = ???
    def getParameter(x$1: String): String = ???
    def getParameterMap(): java.util.Map[String, Array[String]] = ???
    def getParameterNames(): java.util.Enumeration[String] = ???
    def getParameterValues(x$1: String): Array[String] = ???
    def getProtocol(): String = ???
    def getReader(): java.io.BufferedReader = ???
    def getRealPath(x$1: String): String = ???
    def getRemoteAddr(): String = ???
    def getRemoteHost(): String = ???
    def getRemotePort(): Int = ???
    def getRequestDispatcher(x$1: String): javax.servlet.RequestDispatcher = ???
    def getScheme(): String = ???
    def getServerName(): String = ???
    def getServerPort(): Int = ???
    def getServletContext(): javax.servlet.ServletContext = ???
    def isAsyncStarted(): Boolean = ???
    def isAsyncSupported(): Boolean = ???
    def isSecure(): Boolean = ???
    def removeAttribute(x$1: String): Unit = ???
    def setAttribute(x$1: String, x$2: Object): Unit = ???
    def setCharacterEncoding(x$1: String): Unit = ???
    def startAsync(
        x$1: javax.servlet.ServletRequest,
        x$2: javax.servlet.ServletResponse,
    ): javax.servlet.AsyncContext = ???
    def startAsync(): javax.servlet.AsyncContext = ???
  }

}
