/*
 * Copyright 2014–2019 SlamData Inc.
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

package quasar.impl.storage

import slamdata.Predef._

import quasar.concurrent.BlockingContext

import cats.arrow.FunctionK
import cats.effect.{ContextShift, Sync}
import cats.syntax.applicative._
import cats.syntax.functor._
import fs2.Stream
import scalaz.syntax.tag._

import java.util.concurrent.ConcurrentMap
import scala.collection.JavaConverters._

import shims._

final class ConcurrentMapIndexedStore[F[_]: Sync: ContextShift, K, V](
    mp: ConcurrentMap[K, V], blockingPool: BlockingContext)
    extends IndexedStore[F, K, V] {

  private val F = Sync[F]

  private def evalOnPool[A](fa: F[A]): F[A] =
    ContextShift[F].evalOn[A](blockingPool.unwrap)(fa)

  private def evalStreamOnPool[A](s: Stream[F, A]): Stream[F, A] =
    s translate new FunctionK[F, F] {
      def apply[A](fa: F[A]): F[A] = evalOnPool(fa)
    }

  def entries: Stream[F, (K, V)] = for {
    iterator <- Stream.eval(evalOnPool(F.delay(mp.entrySet.iterator.asScala)))
    entry <- evalStreamOnPool(
      Stream.fromIterator[F, java.util.Map.Entry[K, V]](iterator))
  } yield (entry.getKey, entry.getValue)

  def lookup(k: K): F[Option[V]] =
    evalOnPool(F.delay(Option( mp get k )))

  def insert(k: K, v: V): F[Unit] =
    evalOnPool(F.delay(mp.put(k, v))).void

  def delete(k: K): F[Boolean] =
    evalOnPool(F.delay(Option(mp.remove(k)).nonEmpty))
}

object ConcurrentMapIndexedStore {
  def apply[F[_]: Sync: ContextShift, K, V](
      mp: ConcurrentMap[K, V],
      commit: F[Unit],
      blockingPool: BlockingContext)
      : IndexedStore[F, K, V] = {
    val pure = new ConcurrentMapIndexedStore(mp, blockingPool)
    def onUpdate(k: K, v: V): F[Unit] = ContextShift[F].evalOn[Unit](blockingPool.unwrap)(commit)
    def onDelete(k: K, a: Boolean): F[Unit] = ContextShift[F].evalOn[Unit](blockingPool.unwrap)(commit.whenA(a))
    IndexedStore.hooked(pure, onUpdate, onDelete)
  }
  def unhooked[F[_]: Sync: ContextShift, K, V](
      mp: ConcurrentMap[K, V],
      blockingPool: BlockingContext)
      : IndexedStore[F, K, V] = {
    new ConcurrentMapIndexedStore(mp, blockingPool)
  }
}
