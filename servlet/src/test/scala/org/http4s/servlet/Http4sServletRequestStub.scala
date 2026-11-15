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

import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest

final case class HttpServletRequestStub(
    inputStream: ServletInputStream
) extends HttpServletRequest {
  def getInputStream(): ServletInputStream = inputStream

  def authenticate(response: jakarta.servlet.http.HttpServletResponse): Boolean = ???
  def changeSessionId(): String = ???
  def getAuthType(): String = ???
  def getContextPath(): String = ???
  def getCookies(): Array[jakarta.servlet.http.Cookie] = ???
  def getDateHeader(s: String): Long = ???
  def getHeader(s: String): String = ???
  def getHeaderNames(): java.util.Enumeration[String] = ???
  def getHeaders(s: String): java.util.Enumeration[String] = ???
  def getIntHeader(s: String): Int = ???
  def getMethod(): String = ???
  def getPart(s: String): jakarta.servlet.http.Part = ???
  def getParts(): java.util.Collection[jakarta.servlet.http.Part] = ???
  def getPathInfo(): String = ???
  def getPathTranslated(): String = ???
  def getQueryString(): String = ???
  def getRemoteUser(): String = ???
  def getRequestURI(): String = ???
  def getRequestURL(): StringBuffer = ???
  def getRequestedSessionId(): String = ???
  def getServletPath(): String = ???
  def getSession(): jakarta.servlet.http.HttpSession = ???
  def getSession(s: Boolean): jakarta.servlet.http.HttpSession = ???
  def getUserPrincipal(): java.security.Principal = ???
  def isRequestedSessionIdFromCookie(): Boolean = ???
  def isRequestedSessionIdFromURL(): Boolean = ???
  def isRequestedSessionIdFromUrl(): Boolean = ???
  def isRequestedSessionIdValid(): Boolean = ???
  def isUserInRole(s: String): Boolean = ???
  def login(s: String, s2: String): Unit = ???
  def logout(): Unit = ???
  def upgrade[T <: jakarta.servlet.http.HttpUpgradeHandler](s: Class[T]): T = ???
  def getAsyncContext(): jakarta.servlet.AsyncContext = ???
  def getAttribute(s: String): Object = ???
  def getAttributeNames(): java.util.Enumeration[String] = ???
  def getCharacterEncoding(): String = ???
  def getContentLength(): Int = ???
  def getContentLengthLong(): Long = ???
  def getContentType(): String = ???
  def getDispatcherType(): jakarta.servlet.DispatcherType = ???
  def getLocalAddr(): String = ???
  def getLocalName(): String = ???
  def getLocalPort(): Int = ???
  def getLocale(): java.util.Locale = ???
  def getLocales(): java.util.Enumeration[java.util.Locale] = ???
  def getParameter(s: String): String = ???
  def getParameterMap(): java.util.Map[String, Array[String]] = ???
  def getParameterNames(): java.util.Enumeration[String] = ???
  def getParameterValues(s: String): Array[String] = ???
  def getProtocol(): String = ???
  def getReader(): java.io.BufferedReader = ???
  def getRealPath(s: String): String = ???
  def getRemoteAddr(): String = ???
  def getRemoteHost(): String = ???
  def getRemotePort(): Int = ???
  def getRequestDispatcher(s: String): jakarta.servlet.RequestDispatcher = ???
  def getScheme(): String = ???
  def getServerName(): String = ???
  def getServerPort(): Int = ???
  def getServletContext(): jakarta.servlet.ServletContext = ???
  def isAsyncStarted(): Boolean = ???
  def isAsyncSupported(): Boolean = ???
  def isSecure(): Boolean = ???
  def removeAttribute(s: String): Unit = ???
  def setAttribute(s: String, o: Object): Unit = ???
  def setCharacterEncoding(s: String): Unit = ???
  def startAsync(
      request: jakarta.servlet.ServletRequest,
      response: jakarta.servlet.ServletResponse,
  ): jakarta.servlet.AsyncContext = ???
  def startAsync(): jakarta.servlet.AsyncContext = ???
  def getProtocolRequestId(): String = ???
  def getRequestId(): String = ???
  def getServletConnection(): jakarta.servlet.ServletConnection = ???
}
