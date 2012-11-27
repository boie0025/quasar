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
package com.precog.yggdrasil
package table

import com.precog.common.{ Path, VectorCase } 
import com.precog.common.json._
import com.precog.common.security._
import com.precog.bytecode.JType
import com.precog.yggdrasil.util._

import akka.actor.ActorSystem
import akka.dispatch._
import blueeyes.json._
import org.slf4j.{LoggerFactory, MDC}

import scala.annotation.tailrec
import scala.collection.mutable.LinkedHashSet
import scala.util.Random

import scalaz._
import scalaz.effect.IO 
import scalaz.syntax.copointed._
import scalaz.std.anyVal._
import scalaz.std.stream._

import org.specs2._
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.scalacheck._
import org.scalacheck.Gen
import org.scalacheck.Gen._
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary._

import TableModule._
import SampleData._

trait ColumnarTableModuleSpec[M[+_]] extends ColumnarTableModuleTestSupport[M] 
    with TableModuleSpec[M]
    with CogroupSpec[M]
    with CrossSpec[M]
    with TransformSpec[M]
    with CompactSpec[M] 
    with TakeRangeSpec[M]
    with PartitionMergeSpec[M]
    with UnionAllSpec[M]
    with CrossAllSpec[M]
    with DistinctSpec[M] 
    with GroupingGraphSpec[M]
    { spec => 

  //type GroupId = Int
  import trans._
  import constants._
    
  override val defaultPrettyParams = Pretty.Params(2)

  private val groupId = new java.util.concurrent.atomic.AtomicInteger
  def newGroupId = groupId.getAndIncrement

  class Table(slices: StreamT[M, Slice], size: TableSize) extends ColumnarTable(slices, size) {
    import trans._
    def load(apiKey: APIKey, jtpe: JType): M[Table] = sys.error("todo")
    def sort(sortKey: TransSpec1, sortOrder: DesiredSortOrder, unique: Boolean = false) = sys.error("todo")
    def groupByN(groupKeys: Seq[TransSpec1], valueSpec: TransSpec1, sortOrder: DesiredSortOrder = SortAscending, unique: Boolean = false): M[Seq[Table]] = sys.error("todo")
  }
  
  trait TableCompanion extends ColumnarTableCompanion {
    def apply(slices: StreamT[M, Slice], size: TableSize) = new Table(slices, size)

    def singleton(slice: Slice) = new Table(slice :: StreamT.empty[M, Slice], ExactSize(1))

    def align(sourceLeft: Table, alignOnL: TransSpec1, sourceRight: Table, alignOnR: TransSpec1): M[(Table, Table)] = 
      sys.error("not implemented here")
  }

  object Table extends TableCompanion

  private lazy val logger = LoggerFactory.getLogger("com.precog.yggdrasil.table.ColumnarTableModuleSpec")

  "a table dataset" should {
    "verify bijection from static JSON" in {
      val sample: List[JValue] = List(
        JObject(
          JField("key", JArray(JNum(-1L), JNum(0L))),
          JField("value", JNull)
        ), 
        JObject(
          JField("key", JArray(JNum(-3090012080927607325l), JNum(2875286661755661474l))),
          JField("value", JObject(
            JField("q8b", JArray(
              JNum(6.615224799778253E307d), 
              JArray(JBool(false), JNull, JNum(-8.988465674311579E307d), JNum(-3.536399224770604E307d))
            )), 
            JField("lwu",JNum(-5.121099465699862E307d))
          ))
        ), 
        JObject(
          JField("key", JArray(JNum(-3918416808128018609l), JNum(-1L))),
          JField("value", JNum(-1.0))
        )
      )

      val dataset = fromJson(sample.toStream)
      val results = dataset.toJson
      results.copoint must containAllOf(sample).only
    }

    "verify bijection from JSON" in checkMappings(this)
    
    "verify renderJson round tripping" in {
      implicit val gen = sample(schema)
      
      check { data: SampleData =>
        testRenderJson(data.data)
      }.set(minTestsOk -> 20000, workers -> Runtime.getRuntime.availableProcessors)
    }
    
    "handle special cases of renderJson" >> {
      "undefined at beginning of array" >> {
        testRenderJson(JArray(
          JUndefined ::
          JNum(1) ::
          JNum(2) :: Nil) :: Nil)
      }
      
      "undefined in middle of array" >> {
        testRenderJson(JArray(
          JNum(1) ::
          JUndefined ::
          JNum(2) :: Nil) :: Nil)
      }
      
      "fully undefined array" >> {
        testRenderJson(JArray(
          JUndefined ::
          JUndefined ::
          JUndefined :: Nil) :: Nil)
      }
      
      "undefined at beginning of object" >> {
        testRenderJson(JObject(
          JField("foo", JUndefined) ::
          JField("bar", JNum(1)) ::
          JField("baz", JNum(2)) :: Nil) :: Nil)
      }
      
      "undefined in middle of object" >> {
        testRenderJson(JObject(
          JField("foo", JNum(1)) ::
          JField("bar", JUndefined) ::
          JField("baz", JNum(2)) :: Nil) :: Nil)
      }
      
      "fully undefined object" >> {
        //testRenderJson(JObject(
        //  JField("foo", JUndefined) ::
        //  JField("bar", JUndefined) ::
        //  JField("baz", JUndefined) :: Nil) :: Nil)
        testRenderJson(
          JObject(
            Map(
              "foo" -> JUndefined,
              "bar" -> JUndefined,
              "baz" -> JUndefined
            )
          ) :: Nil
        )
      }
      
      "undefined row" >> {
        testRenderJson(
          JObject(
            JField("foo", JUndefined) ::
            JField("bar", JUndefined) ::
            JField("baz", JUndefined) :: Nil) ::
          JNum(42) :: Nil)
      }
      
      "check utf-8 encoding" in check { str: String =>
        val s = str.toList.map((c: Char) => if (c < ' ') ' ' else c).mkString
        testRenderJson(JString(s) :: Nil)
      }.set(minTestsOk -> 20000, workers -> Runtime.getRuntime.availableProcessors)
      
      "check long encoding" in check { ln: Long =>
        testRenderJson(JNum(ln) :: Nil)
      }.set(minTestsOk -> 20000, workers -> Runtime.getRuntime.availableProcessors)
    }
    
    def testRenderJson(seq: Seq[JValue]) = {
      def arr(es: List[JValue]) = if (es.isEmpty) None else Some(JArray(es))

      def minimizeItem(t: (String, JValue)) = minimize(t._2).map((t._1, _))

      def minimize(value: JValue): Option[JValue] = {
        value match {
          case JObject(fields) => Some(JObject(fields.flatMap(minimizeItem)))

          case JArray(Nil) => Some(JArray(Nil))

          case JArray(elements) =>
            val elements2 = elements.flatMap(minimize)
            if (elements2.isEmpty) None else Some(JArray(elements2))

          case JUndefined => None

          case v => Some(v)
        }
      }
    
      val table = fromJson(seq.toStream)
      
      val expected = JArray(seq.toList)
      
      val values = table.renderJson(',') map { _.toString }
      
      val strM = values.foldLeft("") { _ + _ }
      
      val arrayM = strM map { body =>
        val input = "[%s]".format(body)
        JParser.parse(input)
      }
      
      val minimized = minimize(expected) getOrElse JArray(Nil)
      arrayM.copoint mustEqual minimized
    }
    
    "in cogroup" >> {
      "perform a simple cogroup" in testSimpleCogroup(identity[Table])
      "perform another simple cogroup" in testAnotherSimpleCogroup
      "cogroup for unions" in testUnionCogroup
      "perform yet another simple cogroup" in testAnotherSimpleCogroupSwitched
      "cogroup across slice boundaries" in testCogroupSliceBoundaries
      "error on unsorted inputs" in testUnsortedInputs
      "cogroup partially defined inputs properly" in testPartialUndefinedCogroup

      "survive pathology 1" in testCogroupPathology1
      "survive pathology 2" in testCogroupPathology2
      "survive pathology 3" in testCogroupPathology3
      
      "survive scalacheck" in { 
        check { cogroupData: (SampleData, SampleData) => testCogroup(cogroupData._1, cogroupData._2) } 
      }
    }

    "in cross" >> {
      "perform a simple cartesian" in testSimpleCross
      
      "split a cross that would exceed slice boundaries" in {
        val sample: List[JValue] = List(
          JObject(
            JField("key", JArray(JNum(-1L) :: JNum(0L) :: Nil)) ::
            JField("value", JNull) :: Nil
          ), 
          JObject(
            JField("key", JArray(JNum(-3090012080927607325l) :: JNum(2875286661755661474l) :: Nil)) ::
            JField("value", JObject(List(
              JField("q8b", JArray(List(
                JNum(6.615224799778253E307d), 
                JArray(List(JBool(false), JNull, JNum(-8.988465674311579E307d))), JNum(-3.536399224770604E307d)))), 
              JField("lwu",JNum(-5.121099465699862E307d))))
            ) :: Nil
          ), 
          JObject(
            JField("key", JArray(JNum(-3918416808128018609l) :: JNum(-1L) :: Nil)) ::
            JField("value", JNum(-1.0)) :: Nil
          )
        )
        
        val dataset1 = fromJson(sample.toStream, Some(3))
        val dataset2 = fromJson(sample.toStream, Some(3))
        
        dataset1.cross(dataset1)(InnerObjectConcat(Leaf(SourceLeft), Leaf(SourceRight))).slices.uncons.copoint must beLike {
          case Some((head, _)) => head.size must beLessThanOrEqualTo(3)
        }
      }
      
      "cross across slice boundaries on one side" in testCrossSingles
      "survive scalacheck" in {
        check { cogroupData: (SampleData, SampleData) => testCross(cogroupData._1, cogroupData._2) } 
      }
    }

    "in transform" >> {
      "perform the identity transform" in checkTransformLeaf
      "perform a trivial map1" in testMap1IntLeaf
      "fail to map1 into array and object" in testMap1ArrayObject
      "perform a less trvial map1" in checkMap1
      //"give the identity transform for the trivial filter" in checkTrivialFilter
      "give the identity transform for the trivial 'true' filter" in checkTrueFilter
      "give the identity transform for a nontrivial filter" in checkFilter
      "give a transformation for a big decimal and a long" in testMod2Filter
      "perform an object dereference" in checkObjectDeref
      "perform an array dereference" in checkArrayDeref
      "perform metadata dereference on data without metadata" in checkMetaDeref
      "perform a trivial map2 add" in checkMap2Add
      "perform a trivial map2 eq" in checkMap2Eq
      "perform a map2 add over but not into arrays and objects" in testMap2ArrayObject
      "perform a trivial equality check" in checkEqualSelf
      "perform a trivial equality check on an array" in checkEqualSelfArray
      "perform a slightly less trivial equality check" in checkEqual
      "test a failing equality example" in testEqual1
      "perform a simple equality check" in testSimpleEqual
      "perform another simple equality check" in testAnotherSimpleEqual
      "perform yet another simple equality check" in testYetAnotherSimpleEqual
      "perform a equal-literal check" in checkEqualLiteral
      "perform a not-equal-literal check" in checkNotEqualLiteral
      "wrap the results of a transform in an object as the specified field" in checkWrapObject
      "give the identity transform for self-object concatenation" in checkObjectConcatSelf
      "use a right-biased overwrite strategy in object concat conflicts" in checkObjectConcatOverwrite
      "test inner object concat with a single boolean" in testObjectConcatSingletonNonObject
      "test inner object concat with a boolean and an empty object" in testObjectConcatTrivial
      "concatenate dissimilar objects" in checkObjectConcat
      "concatenate dissimilar arrays" in checkArrayConcat
      "delete elements according to a JType" in checkObjectDelete //.set(minTestsOk -> 5000) TODO: saw an error here once
      "perform a trivial type-based filter" in checkTypedTrivial
      "perform a less trivial type-based filter" in checkTyped
      "perform a type-based filter across slice boundaries" in testTypedAtSliceBoundary
      "perform a trivial heterogeneous type-based filter" in testTypedHeterogeneous
      "perform a trivial object type-based filter" in testTypedObject
      "retain all object members when typed to unfixed object" in testTypedObjectUnfixed
      "perform another trivial object type-based filter" in testTypedObject2
      "perform a trivial array type-based filter" in testTypedArray
      "perform another trivial array type-based filter" in testTypedArray2
      "perform yet another trivial array type-based filter" in testTypedArray3
      "perform a fourth trivial array type-based filter" in testTypedArray4
      "perform a trivial number type-based filter" in testTypedNumber
      "perform another trivial number type-based filter" in testTypedNumber2
      "perform a filter returning the empty set" in testTypedEmpty

      "perform a summation scan case 1" in testTrivialScan
      "perform a summation scan of heterogeneous data" in testHetScan
      "perform a summation scan" in checkScan
      "perform dynamic object deref" in testDerefObjectDynamic
      "perform an array swap" in checkArraySwap
      "replace defined rows with a constant" in checkConst
    }

    "in compact" >> {
      "be the identity on fully defined tables"  in testCompactIdentity
      "preserve all defined rows"                in testCompactPreserve
      "have no undefined rows"                   in testCompactRows
      "have no empty slices"                     in testCompactSlices
      "preserve all defined key rows"            in testCompactPreserveKey
      "have no undefined key rows"               in testCompactRowsKey
      "have no empty key slices"                 in testCompactSlicesKey
    }
    
    "in distinct" >> {
      "be the identity on tables with no duplicate rows" in testDistinctIdentity
      "peform properly when the same row appears in two different slices" in testDistinctAcrossSlices
      "peform properly again when the same row appears in two different slices" in testDistinctAcrossSlices2
      "have no duplicate rows" in testDistinct
    }

    "in takeRange" >> {
      "select the correct rows in a trivial case" in testTakeRange
      "select the correct rows when we take past the end of the table" in testTakeRangeLarger
      "select the correct rows when we start at an index larger than the size of the table" in testTakeRangeEmpty
      "select the correct rows across slice boundary" in testTakeRangeAcrossSlices
      "select the correct rows only in second slice" in testTakeRangeSecondSlice
      "select the first slice" in testTakeRangeFirstSliceOnly
      "select nothing with a negative starting index" in testTakeRangeNegStart
      "select nothing with a negative number to take" in testTakeRangeNegNumber
      "select the correct rows using scalacheck" in checkTakeRange
    }
  }

  "partitionMerge" should {
    "concatenate reductions of subsequences" in testPartitionMerge
  }

  "unionAll" should {
    "union a simple homogeneous borg result set" in simpleUnionAllTest
    "union a simple reversed borg result set" in reversedUnionAllTest
  }

  "crossAll" should {
    "cross a simple borg result set" in simpleCrossAllTest
  }

  "logging" should {
    "run" in {
      testSimpleCogroup(t => t.logged(logger, "test-logging", "start stream", "end stream") {
        slice => "size: " + slice.size
      })
    }
  }

  "grouping support" >> {
    import Table._
    import Table.Universe._

    def constraint(str: String) = OrderingConstraint(str.split(",").toSeq.map(_.toSet.map((c: Char) => CPathField(c.toString))))
    def ticvars(str: String) = str.toSeq.map((c: Char) => CPathField(c.toString))
    def order(str: String) = OrderingConstraint.fromFixed(ticvars(str))
    def mergeNode(str: String) = MergeNode(ticvars(str).toSet, null)

    "derive the universes of binding constraints" >> {
      "single-source groupings should generate single binding universes" in {
        val spec = GroupingSource(
          Table.empty, 
          SourceKey.Single, Some(TransSpec1.Id), 2, 
          GroupKeySpecSource(CPathField("1"), TransSpec1.Id))

        Table.findBindingUniverses(spec) must haveSize(1)
      }
      
      "single-source groupings should generate single binding universes if no disjunctions are present" in {
        val spec = GroupingSource(
          Table.empty,
          SourceKey.Single, Some(SourceValue.Single), 3,
          GroupKeySpecAnd(
            GroupKeySpecSource(CPathField("1"), DerefObjectStatic(Leaf(Source), CPathField("a"))),
            GroupKeySpecSource(CPathField("2"), DerefObjectStatic(Leaf(Source), CPathField("b")))))

        Table.findBindingUniverses(spec) must haveSize(1)
      }
      
      "multiple-source groupings should generate single binding universes if no disjunctions are present" in {
        val spec1 = GroupingSource(
          Table.empty,
          SourceKey.Single, Some(TransSpec1.Id), 2,
          GroupKeySpecSource(CPathField("1"), TransSpec1.Id))
          
        val spec2 = GroupingSource(
          Table.empty,
          SourceKey.Single, Some(TransSpec1.Id), 3,
          GroupKeySpecSource(CPathField("1"), TransSpec1.Id))
          
        val union = GroupingAlignment(
          DerefObjectStatic(Leaf(Source), CPathField("1")),
          DerefObjectStatic(Leaf(Source), CPathField("1")),
          spec1,
          spec2, GroupingSpec.Union)

        Table.findBindingUniverses(union) must haveSize(1)
      }

      "single-source groupings should generate a number of binding universes equal to the number of disjunctive clauses" in {
        val spec = GroupingSource(
          Table.empty,
          SourceKey.Single, Some(SourceValue.Single), 3,
          GroupKeySpecOr(
            GroupKeySpecSource(CPathField("1"), DerefObjectStatic(Leaf(Source), CPathField("a"))),
            GroupKeySpecSource(CPathField("2"), DerefObjectStatic(Leaf(Source), CPathField("b")))))

        Table.findBindingUniverses(spec) must haveSize(2)
      }
      
      "multiple-source groupings should generate a number of binding universes equal to the product of the number of disjunctive clauses from each source" in {
        val spec1 = GroupingSource(
          Table.empty,
          SourceKey.Single, Some(TransSpec1.Id), 2,
          GroupKeySpecOr(
            GroupKeySpecSource(CPathField("1"), DerefObjectStatic(Leaf(Source), CPathField("a"))),
            GroupKeySpecSource(CPathField("2"), DerefObjectStatic(Leaf(Source), CPathField("b")))))
          
        val spec2 = GroupingSource(
          Table.empty,
          SourceKey.Single, Some(TransSpec1.Id), 3,
          GroupKeySpecOr(
            GroupKeySpecSource(CPathField("1"), DerefObjectStatic(Leaf(Source), CPathField("a"))),
            GroupKeySpecSource(CPathField("2"), DerefObjectStatic(Leaf(Source), CPathField("b")))))
          
        val union = GroupingAlignment(
          DerefObjectStatic(Leaf(Source), CPathField("1")),
          DerefObjectStatic(Leaf(Source), CPathField("1")),
          spec1,
          spec2, GroupingSpec.Union)

        Table.findBindingUniverses(union) must haveSize(4)
      }
    }

    "derive a correct TransSpec for a conjunctive GroupKeySpec" in {
      val keySpec = GroupKeySpecAnd(
        GroupKeySpecAnd(
          GroupKeySpecSource(CPathField("tica"), DerefObjectStatic(SourceValue.Single, CPathField("a"))),
          GroupKeySpecSource(CPathField("ticb"), DerefObjectStatic(SourceValue.Single, CPathField("b")))),
        GroupKeySpecSource(CPathField("ticc"), DerefObjectStatic(SourceValue.Single, CPathField("c"))))

      val transspec = GroupKeyTrans(Table.Universe.sources(keySpec))
      val JArray(data) = JParser.parse("""[
        {"key": [1], "value": {"a": 12, "b": 7}},
        {"key": [2], "value": {"a": 42}},
        {"key": [1], "value": {"a": 13, "c": true}}
      ]""")

      val JArray(expected) = JParser.parse("""[
        {"000000": 12, "000001": 7},
        {"000000": 42},
        {"000000": 13, "000002": true}
      ]""")

      fromJson(data.toStream).transform(transspec.spec).toJson.copoint must_== expected
    }

    "find the maximal spanning forest of a set of merge trees" in {
      import Table.Universe._

      val abcd = MergeNode(ticvars("abcd").toSet, null)
      val abc = MergeNode(ticvars("abc").toSet, null)
      val ab = MergeNode(ticvars("ab").toSet, null)
      val ac = MergeNode(ticvars("ac").toSet, null)
      val a = MergeNode(ticvars("a").toSet, null)
      val e = MergeNode(ticvars("e").toSet, null)

      val connectedNodes = Set(abcd, abc, ab, ac, a)
      val allNodes = connectedNodes + e
      val result = findSpanningGraphs(edgeMap(allNodes))

      result.toList must beLike {
        case MergeGraph(n1, e1) :: MergeGraph(n2, e2) :: Nil =>
          val (nodes, edges) = if (n1 == Set(e)) (n2, e2) else (n1, e1)

          nodes must haveSize(5)
          edges must haveSize(4) 
          edges.map(_.sharedKeys.size) must_== Set(3, 2, 2, 1)
      }
    }

    "find the maximal spanning forest of a set of merge trees" in {
      import Table.Universe._

      val ab = MergeNode(ticvars("ab").toSet, null)
      val bc = MergeNode(ticvars("bc").toSet, null)
      val ac = MergeNode(ticvars("ac").toSet, null)

      val connectedNodes = Set(ab, bc, ac)
      val result = findSpanningGraphs(edgeMap(connectedNodes))

      result must haveSize(1)
      result.head.nodes must_== connectedNodes

      val expectedUnorderedEdges = edgeMap(connectedNodes).values.flatten.toSet
      forall(result.head.edges) { edge =>
        (expectedUnorderedEdges must contain(edge)) //or
        //(expectedUnorderedEdges must contain(edge.reverse))
      }
    }

    "binding constraints" >> {
      import Table.OrderingConstraints._

      "minimize" >> {
        "minimize to multiple sets" in {
          val abcd = constraint("abcd")
          val abc = constraint("abc")
          val ab = constraint("ab")
          val ac = constraint("ac")

          val expected = Set(
            constraint("ab,c,d"),
            constraint("ac")
          )

          minimize(Set(abcd, abc, ab, ac)) must_== expected
        }

        "minimize to multiple sets with a singleton" in {
          val abcd = constraint("abcd")
          val abc = constraint("abc")
          val ab = constraint("ab")
          val ac = constraint("ac")
          val c = constraint("c")

          val expected = Set(
            constraint("c,a,b,d"),
            constraint("ab")
          )

          minimize(Set(abcd, abc, ab, ac, c)) must_== expected
        }

        "not minimize completely disjoint constraints" in {
          val ab = constraint("ab")
          val bc = constraint("bc")
          val ca = constraint("ca")

          val expected = Set(
            constraint("ab"),
            constraint("bc"),
            constraint("ca")
          )

          minimize(Set(ab, bc, ca)) must_== expected
        }
      }

      "find required sorts" >> {
        "simple sort" in {
          val abcd = MergeNode(ticvars("abcd").toSet, null)
          val abc = MergeNode(ticvars("abc").toSet, null)
          val ab = MergeNode(ticvars("ab").toSet, null)
          val ac = MergeNode(ticvars("ac").toSet, null)
          val a = MergeNode(ticvars("a").toSet, null)

          val spanningGraph = findSpanningGraphs(edgeMap(Set(abcd, abc, ab, ac, a))).head

          def checkPermutation(nodeList: List[MergeNode]) = {
            val requiredSorts = findRequiredSorts(spanningGraph, nodeList)

            requiredSorts(a) must_== Set(ticvars("a"))
            requiredSorts(ac) must_== Set(ticvars("ac"))
            requiredSorts(ab) must_== Set(ticvars("ab"))
            (requiredSorts(abc), requiredSorts(abcd)) must beLike {
              case (sabc, sabcd) =>
                (
                  (sabc == Set(ticvars("abc")) && (sabcd == Set(ticvars("abc"), ticvars("ac")))) ||
                  (sabc == Set(ticvars("acb")) && (sabcd == Set(ticvars("acb"), ticvars("ab")))) ||
                  (sabc == Set(ticvars("abc"), ticvars("ac")) && (sabcd == Set(ticvars("abc")))) ||
                  (sabc == Set(ticvars("acb"), ticvars("ab")) && (sabcd == Set(ticvars("acb")))) 
                ) must beTrue
            }
          }

          forall(spanningGraph.nodes.toList.permutations) { nodeList =>
            checkPermutation(nodeList)
          }
        }

        "in a cycle" in {
          val ab = MergeNode(ticvars("ab").toSet, null)
          val ac = MergeNode(ticvars("ac").toSet, null)
          val bc = MergeNode(ticvars("bc").toSet, null)

          val spanningGraph = findSpanningGraphs(edgeMap(Set(ab, ac, bc))).head

          forall(spanningGraph.nodes.toList.permutations) { nodeList =>
            val requiredSorts = findRequiredSorts(spanningGraph, nodeList)

            requiredSorts(ab) must_== Set(ticvars("a"), ticvars("b"))
            requiredSorts(ac) must_== Set(ticvars("a"), ticvars("c"))
            requiredSorts(bc) must_== Set(ticvars("b"), ticvars("c"))
          }
        }

        "in connected cycles" in {
          val ab = MergeNode(ticvars("ab").toSet, null)
          val ac = MergeNode(ticvars("ac").toSet, null)
          val bc = MergeNode(ticvars("bc").toSet, null)
          val ad = MergeNode(ticvars("ad").toSet, null)
          val db = MergeNode(ticvars("db").toSet, null)

          val spanningGraph = findSpanningGraphs(edgeMap(Set(ab, ac, bc, ad, db))).head

          forall(spanningGraph.nodes.toList.permutations) { nodeList =>
            val requiredSorts = findRequiredSorts(spanningGraph, nodeList)

            requiredSorts(ab) must_== Set(ticvars("a"), ticvars("b"))
            requiredSorts(ac) must_== Set(ticvars("a"), ticvars("c"))
            requiredSorts(bc) must_== Set(ticvars("b"), ticvars("c"))
            requiredSorts(ad) must_== Set(ticvars("a"), ticvars("d"))
            requiredSorts(db) must_== Set(ticvars("d"), ticvars("b"))
          }
        }

        "in a connected cycle with extraneous constraints" in {
          val ab = MergeNode(ticvars("ab").toSet, null)
          val ac = MergeNode(ticvars("ac").toSet, null)
          val bc = MergeNode(ticvars("bc").toSet, null)
          val ad = MergeNode(ticvars("ad").toSet, null)

          val spanningGraph = findSpanningGraphs(edgeMap(Set(ab, ac, bc, ad))).head

          forall(spanningGraph.nodes.toList.permutations) { nodeList =>
            val requiredSorts = findRequiredSorts(spanningGraph, nodeList)

            requiredSorts(ab) must_== Set(ticvars("a"), ticvars("b"))
            requiredSorts(ac) must_== Set(ticvars("a"), ticvars("c"))
            requiredSorts(bc) must_== Set(ticvars("b"), ticvars("c"))
            requiredSorts(ad) must_== Set(ticvars("a"))
          }
        }
      }
    }

    "transform a group key transspec to use a desired sort key order" in {
      import GroupKeyTrans._

      val trans = GroupKeyTrans(
        OuterObjectConcat(
          WrapObject(DerefObjectStatic(SourceValue.Single, CPathField("a")), keyName(0)),
          WrapObject(DerefObjectStatic(SourceValue.Single, CPathField("b")), keyName(1)),
          WrapObject(DerefObjectStatic(SourceValue.Single, CPathField("c")), keyName(2))
        ),
        ticvars("abc")
      )

      val JArray(data) = JParser.parse("""[
        {"key": [1], "value": {"a": 12, "b": 7}},
        {"key": [2], "value": {"a": 42}},
        {"key": [1], "value": {"a": 13, "c": true}}
      ]""")

      val JArray(expected) = JParser.parse("""[
        {"000001": 12, "000002": 7},
        {"000001": 42},
        {"000001": 13, "000000": true}
      ]""")

      val alignedSpec = trans.alignTo(ticvars("ca")).spec
      fromJson(data.toStream).transform(alignedSpec).toJson.copoint must_== expected
    }

    "track table metrics" in {
      "single traversal" >> {
        implicit val gen = sample(objectSchema(_, 3))
        check { (sample: SampleData) =>
          val expectedSlices = (sample.data.size.toDouble / defaultSliceSize).ceil

          val table = fromSample(sample)
          val t0 = table.transform(TransSpec1.Id)
          t0.toJson.copoint must_== sample.data

          table.metrics.startCount must_== 1
          table.metrics.sliceTraversedCount must_== expectedSlices
          t0.metrics.startCount must_== 1
          t0.metrics.sliceTraversedCount must_== expectedSlices
        }
      }

      "multiple transforms" >> {
        implicit val gen = sample(objectSchema(_, 3))
        check { (sample: SampleData) =>
          val expectedSlices = (sample.data.size.toDouble / defaultSliceSize).ceil

          val table = fromSample(sample)
          val t0 = table.transform(TransSpec1.Id).transform(TransSpec1.Id).transform(TransSpec1.Id)
          t0.toJson.copoint must_== sample.data

          table.metrics.startCount must_== 1
          table.metrics.sliceTraversedCount must_== expectedSlices
          t0.metrics.startCount must_== 1
          t0.metrics.sliceTraversedCount must_== expectedSlices
        }
      }

      "multiple forcing calls" >> {
        implicit val gen = sample(objectSchema(_, 3))
        check { (sample: SampleData) =>
          val expectedSlices = (sample.data.size.toDouble / defaultSliceSize).ceil

          val table = fromSample(sample)
          val t0 = table.compact(TransSpec1.Id).compact(TransSpec1.Id).compact(TransSpec1.Id)
          table.toJson.copoint must_== sample.data
          t0.toJson.copoint must_== sample.data

          table.metrics.startCount must_== 2
          table.metrics.sliceTraversedCount must_== (expectedSlices * 2)
          t0.metrics.startCount must_== 1
          t0.metrics.sliceTraversedCount must_== expectedSlices
        }
      }
    }
  }
}

object ColumnarTableModuleSpec extends ColumnarTableModuleSpec[Free.Trampoline] {
  implicit def M = Trampoline.trampolineMonad

  type YggConfig = IdSourceConfig with ColumnarTableModuleConfig
  val yggConfig = new IdSourceConfig with ColumnarTableModuleConfig {
    val maxSliceSize = 10
    
    val idSource = new IdSource {
      private val source = new java.util.concurrent.atomic.AtomicLong
      def nextId() = source.getAndIncrement
    }
  }
}


// vim: set ts=4 sw=4 et:
