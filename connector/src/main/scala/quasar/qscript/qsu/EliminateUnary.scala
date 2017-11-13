/*
 * Copyright 2014–2017 SlamData Inc.
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

package quasar.qscript.qsu

import quasar.qscript.qsu.{QScriptUniform => QSU}

import matryoshka.BirecursiveT
import scalaz.Free
import scalaz.syntax.applicative._

final class EliminateUnary[T[_[_]]: BirecursiveT] private () extends QSUTTypes[T] {
  import QSUGraph.Extractors._

  def apply(qgraph: QSUGraph): QSUGraph = qgraph rewrite {
    case Unary(source, mf) =>
      qgraph.overwriteAtRoot(QSU.Map(source.root, Free.roll(mf.map(_.point[FreeMapA]))))
  }
}

object EliminateUnary {
  def apply[T[_[_]]: BirecursiveT]: EliminateUnary[T] = new EliminateUnary[T]
}
