/*
 *  ____    ____    _____    ____    ___     ____
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog

import blueeyes._
import scalaz.{ Bind, Monoid }
import java.util.Comparator
import scala.collection.JavaConverters._
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

package util {
  /**
    * Opaque symbolic identifier (like Int, but better!).
    */
  final class Identifier extends AnyRef

  // Shared Int could easily overflow: Unshare? Extend to a Long? Different approach?
  object IdGen extends IdGen
  class IdGen {
    private[this] val currentId = new AtomicInteger(0)
    def nextInt(): Int = currentId.getAndIncrement()
  }

  class Order2JComparator[A](order: scalaz.Order[A]) {
    def toJavaComparator: Comparator[A] = new Comparator[A] {
      def compare(a1: A, a2: A) = {
        order.order(a1, a2).toInt
      }
    }
  }

  final class LazyMap[A, B, C](source: Map[A, B], f: B => C) extends Map[A, C] {
    private val m = new ConcurrentHashMap[A, C]()

    def iterator: Iterator[(A, C)] = source.keysIterator map { a =>
      (a, apply(a))
    }

    def get(a: A): Option[C] = m get a match {
      case null =>
        source get a map { b =>
          val c = f(b)
          m.putIfAbsent(a, c)
          c
        }
      case x => Some(x)
    }

    def +[C1 >: C](kv: (A, C1)): Map[A, C1] = iterator.toMap + kv
    def -(a: A): Map[A, C]                  = iterator.toMap - a
  }

  sealed trait LazyMapValues[A, B] {
    protected def source: Map[A, B]
    def lazyMapValues[C](f: B => C): Map[A, C] = new LazyMap[A, B, C](source, f)
  }
}

package object util {
  type RawBitSet = Array[Int]

  def flipBytes(buffer: ByteBuffer): Array[Byte] = {
    val bytes = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    buffer.flip()
    bytes
  }
  def arrayEq[@specialized A](a1: Array[A], a2: Array[A]): Boolean = {
    val len = a1.length
    if (len != a2.length) return false
    var i = 0
    while (i < len) {
      if (a1(i) != a2(i)) return false
      i += 1
    }
    true
  }

  implicit def bitSetOps(bs: BitSet): BitSetUtil.BitSetOperations = new BitSetUtil.BitSetOperations(bs)

  implicit def Order2JComparator[A](order: scalaz.Order[A]): Order2JComparator[A] = new Order2JComparator(order)

  implicit def vectorMonoid[A]: Monoid[Vector[A]] = new Monoid[Vector[A]] {
    def zero: Vector[A]                         = Vector.empty[A]
    def append(v1: Vector[A], v2: => Vector[A]) = v1 ++ v2
  }

  implicit def bigDecimalMonoid: Monoid[BigDecimal] = new Monoid[BigDecimal] {
    def zero: BigDecimal                                      = BigDecimal(0)
    def append(v1: BigDecimal, v2: => BigDecimal): BigDecimal = v1 + v2
  }

  implicit def lazyValueMapper[A, B](m: Map[A, B]) = new LazyMapValues[A, B] { val source = m }
  implicit val InstantOrdering: Ordering[Instant] = Ordering.Long.on[Instant](_.getMillis)

  implicit def FutureBind(implicit ctx: ExecutionContext): Bind[Future] = new Bind[Future] {
    def map[A, B](fut: Future[A])(f: A => B)          = fut.map(f)
    def bind[A, B](fut: Future[A])(f: A => Future[B]) = fut.flatMap(f)
  }
}
