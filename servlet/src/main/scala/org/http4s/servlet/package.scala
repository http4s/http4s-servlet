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

import cats.Applicative
import cats.effect.Async

package object servlet {
  protected[servlet] type BodyWriter[F[_]] = Response[F] => F[Unit]

  @deprecated("Use nullBodyWriter with Applicative constraint", "0.23.12")
  protected[servlet] def NullBodyWriter[F[_]](implicit F: Async[F]): BodyWriter[F] =
    nullBodyWriter[F]

  private[servlet] def nullBodyWriter[F[_]](implicit F: Applicative[F]): BodyWriter[F] =
    _ => F.unit

  protected[servlet] val DefaultChunkSize = 4096
}