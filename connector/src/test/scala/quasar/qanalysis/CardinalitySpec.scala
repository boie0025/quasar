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

package quasar.qanalysis

import quasar.Predef._
import quasar.fp.ski.κ
import quasar.contrib.pathy.{AFile, ADir, APath}
import quasar.qscript._
import quasar.qscript.MapFuncs._
import quasar.common.SortDir

import matryoshka.data.Fix
import pathy.Path._
import org.specs2.scalaz.DisjunctionMatchers
import scalaz._, Scalaz._

class CardinalitySpec extends quasar.Qspec with QScriptHelpers with DisjunctionMatchers {

  sequential

  val empty: APath => Int = κ(0)

  "Cardinality" should {

    "Read" should {
      "always returns 1 for any file" in {
        val compile = Cardinality.read[AFile].calculate(empty)
        val afile = rootDir </> dir("path") </> dir("to") </> file("file")
        compile(Const[Read[AFile], Int](Read(afile))) must_== 1
      }

      "always returns 1 for any dir" in {
        val compile = Cardinality.read[ADir].calculate(empty)
        val adir = rootDir </> dir("path") </> dir("to") </> dir("dir")
        compile(Const[Read[ADir], Int](Read(adir))) must_== 1
      }
    }

    "ShiftedRead" should {
      "returns what 'pathCard' is returning for given file" in {
        val fileCardinality = 50
        val pathCard = κ(fileCardinality)
        val compile = Cardinality.shiftedReadFile.calculate(pathCard)
        val afile = rootDir </> dir("path") </> dir("to") </> file("file")
        compile(Const[ShiftedRead[AFile], Int](ShiftedRead(afile, ExcludeId))) must_== fileCardinality
      }

      "returns what 'pathCard' is returning for given dir" in {
        val dirCardinality = 55
        val pathCard = κ(dirCardinality)
        val compile = Cardinality.shiftedReadDir.calculate(pathCard)
        val adir = rootDir </> dir("path") </> dir("to") </> dir("dir")
        compile(Const[ShiftedRead[ADir], Int](ShiftedRead(adir, ExcludeId))) must_== dirCardinality
      }
    }

    "QScriptCore" should {
      val compile = Cardinality.qscriptCore[Fix].calculate(empty)
      "Map" should {
        "returns cardinality of already processed part of qscript" in {
          val cardinality = 40
          val map = quasar.qscript.Map(cardinality, ProjectFieldR(HoleF, StrLit("field")))
          compile(map) must_== cardinality
        }
      }
      "Reduce" should {
        "returns cardinality of 1" in {
          pending
        }
      }
      "Sort" should {
        "returns cardinality of already processed part of qscript" in {
          val cardinality = 60
          def bucket = ProjectFieldR(HoleF, StrLit("field"))
          def order = (bucket, SortDir.asc).wrapNel
          val sort = quasar.qscript.Sort(cardinality, bucket, order)
          compile(sort) must_== cardinality
        }
      }
      "Filter" should {
        "returns half of cardinality of already processed part of qscript" in {
          val cardinality = 50
          def func: FreeMap = Free.roll(Lt(ProjectFieldR(HoleF, StrLit("age")), IntLit(24)))
          val filter = quasar.qscript.Filter(cardinality, func)
          compile(filter) must_== cardinality / 2
        }
      }
      "Subset" should {
        "returns cardinality of _" in {
          pending
        }
      }
      "LeftShift" should {
        "returns cardinality of _" in {
          pending
        }
      }
      "Union" should {
        "returns cardinality of sum lBranch + rBranch" in {
          val cardinality = 100
          def func(country: String): FreeMap =
            Free.roll(MapFuncs.Eq(ProjectFieldR(HoleF, StrLit("country")), StrLit(country)))
          def left: FreeQS = Free.roll(QCT.inj(quasar.qscript.Map(HoleQS, ProjectFieldR(HoleF, StrLit("field")))))
          def right: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("US"))))
          val union = quasar.qscript.Union(cardinality, left, right)
          compile(union) must_== cardinality + (cardinality / 2)
        }
      }
      "Unreferenced" should {
        "returns cardinality of 1" in {
          compile(Unreferenced()) must_== 1
        }
      }
    }

    "ProjectBucket"  should {
      "returns cardinality of 1" in {
        val compile = Cardinality.projectBucket[Fix].calculate(empty)
        def func: FreeMap = Free.roll(Lt(ProjectFieldR(HoleF, StrLit("age")), IntLit(24)))
        val bucket = BucketField(0, func, func)
        compile(bucket) must_== 0
      }
    }

    "EquiJoin" should {
      "returns cardinality of multiplication lBranch * rBranch" in {
        val compile = Cardinality.equiJoin[Fix].calculate(empty)
        val cardinality = 100
        val func: FreeMap =
          Free.roll(MapFuncs.Eq(ProjectFieldR(HoleF, StrLit("field")), StrLit("val")))
        def left: FreeQS = Free.roll(QCT.inj(quasar.qscript.Map(HoleQS, ProjectFieldR(HoleF, StrLit("field")))))
        def right: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func)))
        val joinFunc: JoinFunc = (LeftSide : JoinSide).point[Free[MapFunc, ?]]
        val join = quasar.qscript.EquiJoin(cardinality, left, right, func, func, Inner, joinFunc)
        compile(join) must_== cardinality * (cardinality / 2)
      }
    }

    "ThetaJoin" should {
      "return cardinality of multiplication lBranch * rBranch" in {
        val compile = Cardinality.thetaJoin[Fix].calculate(empty)
        val cardinality = 100
        val func: FreeMap =
          Free.roll(MapFuncs.Eq(ProjectFieldR(HoleF, StrLit("field")), StrLit("val")))
        def left: FreeQS = Free.roll(QCT.inj(quasar.qscript.Map(HoleQS, ProjectFieldR(HoleF, StrLit("field")))))
        def right: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func)))
        val joinFunc: JoinFunc = (LeftSide : JoinSide).point[Free[MapFunc, ?]]
        val join = quasar.qscript.ThetaJoin(cardinality, left, right, joinFunc, Inner, joinFunc)
        compile(join) must_== cardinality * (cardinality / 2)
      }
    }

    "DeadEnd" should {
      "return cardinality of 1" in {
        val compile = Cardinality.deadEnd.calculate(empty)
        compile(Const(Root)) must_== 1
      }
    }

  }


}
