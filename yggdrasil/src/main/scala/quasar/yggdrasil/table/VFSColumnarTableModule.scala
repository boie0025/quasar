/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.yggdrasil.table

import quasar.blueeyes.json.JValue
import quasar.contrib.pathy.{firstSegmentName, ADir, AFile, APath, PathSegment}
import quasar.contrib.fs2._
import quasar.contrib.scalaz.concurrent._
import quasar.fs.MoveSemantics
import quasar.niflheim.NIHDB
import quasar.precog.common.{Path => PrecogPath}
import quasar.yggdrasil.{ExactSize, Schema}
import quasar.yggdrasil.bytecode.JType
import quasar.yggdrasil.nihdb.NIHDBProjection
import quasar.yggdrasil.vfs._

import scala.concurrent.ExecutionContext.Implicits.global // FIXME what is this thing
import scala.concurrent.duration._

import java.util.concurrent.{ConcurrentHashMap, ScheduledThreadPoolExecutor, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, ActorSystem}

import cats.effect._

import delorean._

import fs2.Stream
import fs2.interop.scalaz._

import org.slf4s.Logging

import pathy.Path

import scalaz.{-\/, \/, EitherT, Monad, OptionT, StreamT}
import scalaz.concurrent.Task
import scalaz.std.list._
import scalaz.syntax.either._
import scalaz.syntax.monad._
import scalaz.syntax.traverse._

trait VFSColumnarTableModule extends BlockStoreColumnarTableModule[IO] with Logging {
  private type ET[F[_], A] = EitherT[F, ResourceError, A]

  private val dbs = new ConcurrentHashMap[(Blob, Version), NIHDB]

  // TODO 20 is the old default size; need to actually tune this
  private val TxLogScheduler = new ScheduledThreadPoolExecutor(20,
    new ThreadFactory {
      private val counter = new AtomicInteger(0)

      def newThread(r: Runnable): Thread = {
        val t = new Thread(r)
        t.setName("HOWL-sched-%03d".format(counter.getAndIncrement()))

        t
      }
    })

  def CookThreshold: Int
  def StorageTimeout: FiniteDuration

  def actorSystem: ActorSystem
  private[this] implicit def _actorSystem = actorSystem

  def vfs: SerialVFS

  def masterChef: ActorRef

  def openDB(path: AFile): OptionT[IO, ResourceError \/ NIHDB] = {
    val failure = ResourceError.fromExtractorError(s"failed to open NIHDB in path $path")

    for {
      blob <- OptionT(runToIO(vfs.readPath(path)))
      version <- OptionT(runToIO(vfs.headOfBlob(blob)))

      cached <- IO(Option(dbs.get((blob, version)))).liftM[OptionT]

      nihdb <- cached match {
        case Some(nihdb) =>
          nihdb.right[ResourceError].point[OptionT[IO, ?]]

        case None =>
          for {
            dir <- runToIO(vfs.underlyingDir(blob, version)).liftM[OptionT]

            tndb = IO {
              NIHDB.open(
                masterChef,
                dir,
                CookThreshold,
                StorageTimeout,
                TxLogScheduler).unsafePerformIO()
            }

            validation <- OptionT(tndb)

            back <- validation.disjunction.leftMap(failure) traverse { nihdb: NIHDB =>
              for {
                check <- IO(dbs.putIfAbsent((blob, version), nihdb)).liftM[OptionT]

                back <- if (check == null)
                  nihdb.right[ResourceError].point[OptionT[IO, ?]]
                else
                  IO.fromFuture(IO(nihdb.close)).liftM[OptionT] >> openDB(path)
              } yield back
            }
          } yield back.flatMap(x => x)
      }
    } yield nihdb
  }

  def createDB(path: AFile): IO[ResourceError \/ (Blob, Version, NIHDB)] = {
    val failure = ResourceError.fromExtractorError(s"Failed to create NIHDB in $path")

    val createBlob: IO[Blob] = for {
      blob <- vfs.scratch
      _ <- vfs.link(blob, path)   // should be `true` because we already looked for path
    } yield blob

    for {
      maybeBlob <- vfs.readPath(path)
      blob <- maybeBlob.map(IO(_)).getOrElse(createBlob)

      version <- vfs.fresh(blob)    // overwrite any previously-existing HEAD
      dir <- vfs.underlyingDir(blob, version)

      validation <- IO {
        NIHDB.create(
          masterChef,
          dir,
          CookThreshold,
          StorageTimeout,
          TxLogScheduler).unsafePerformIO()
      }

      disj = validation.disjunction.leftMap(failure)
    } yield disj.map(n => (blob, version, n))
  }

  def commitDB(blob: Blob, version: Version, db: NIHDB): IO[Unit] = {
    // last-wins semantics
    lazy val replaceM: OptionT[IO, Unit] = for {
      oldHead <- OptionT(vfs.headOfBlob(blob))
      oldDB <- OptionT(IO(Option(dbs.get((blob, oldHead)))))

      check <- IO(dbs.replace((blob, oldHead), oldDB, db)).liftM[OptionT]

      _ <- if (check)
        IO.fromFuture(IO(oldDB.close)).liftM[OptionT]
      else
        replaceM
    } yield ()

    val insert = for {
      check <- IO(dbs.putIfAbsent((blob, version), db))

      _ <- if (check == null)
        vfs.commit(blob, version)
      else
        commitDB(blob, version, db)
    } yield ()

    for {
      _ <- IO(log.trace(s"attempting to commit blob/version: $blob/$version"))
      _ <- replaceM.getOrElseF(insert)
    } yield ()
  }

  def ingest(path: PrecogPath, ingestion: Stream[IO, JValue]): EitherT[IO, ResourceError, Unit] = {
    for {
      afile <- pathToAFileET[IO](path)
      triple <- EitherT.eitherT(createDB(afile))
      (blob, version, nihdb) = triple

      actions = ingestion.chunks.zipWithIndex evalMap {
        case (jvs, offset) =>
          // insertVerified forces sequential behavior and backpressure
          IO.fromFuture(IO(nihdb.insertVerified(NIHDB.Batch(offset, jvs.toList) :: Nil)))
      }

      driver = actions.drain ++ Stream.eval(commitDB(blob, version, nihdb))

      _ <- EitherT.rightT[IO, ResourceError, Unit](driver.run)
    } yield ()
  }

  // note: this function should almost always be unnecessary, since nihdb includes the append log in snapshots
  def flush(path: AFile): IO[Unit] = {
    val ot = for {
      blob <- OptionT(runToIO(vfs.readPath(path)))
      head <- OptionT(runToIO(vfs.headOfBlob(blob)))
      db <- OptionT(IO(Option(dbs.get((blob, head)))))
      _ <- IO.fromFuture(IO(db.cook)).liftM[OptionT]
    } yield ()

    ot.getOrElse(IO.unit)
  }

  object fs {
    def listContents(dir: ADir): Task[Set[PathSegment]] = {
      vfs.ls(dir) map { paths =>
        paths.map(firstSegmentName).flatMap(_.toList).toSet
      }
    }

    def exists(path: APath): Task[Boolean] = vfs.exists(path)

    def moveFile(from: AFile, to: AFile, semantics: MoveSemantics): Task[Boolean] =
      vfs.moveFile(from, to, semantics)

    def moveDir(from: ADir, to: ADir, semantics: MoveSemantics): Task[Boolean] =
      vfs.moveDir(from, to, semantics)

    def delete(target: APath): Task[Boolean] = {
      def deleteFile(target: AFile) = {
        for {
          _ <- {
            val ot = for {
              blob <- OptionT(vfs.readPath(target))
              version <- OptionT(vfs.headOfBlob(blob))
              nihdb <- OptionT(Task.delay(Option(dbs.get((blob, version)))))

              removed <- Task.delay(dbs.remove((blob, version), nihdb)).liftM[OptionT]

              _ <- if (removed)
                nihdb.close.toTask.liftM[OptionT]
              else
                ().point[OptionT[Task, ?]]
            } yield ()

            ot.run
          }

          back <- vfs.delete(target)
        } yield back
      }

      def deleteDir(target: ADir): Task[Boolean] = {
        for {
          paths <- vfs.ls(target)

          results <- paths traverse { path =>
            delete(target </> path)
          }
        } yield results.forall(_ == true)
      }

      Path.refineType(target).fold(deleteDir, deleteFile)
    }
  }

  private def pathToAFile(path: PrecogPath): Option[AFile] = {
    val parent = path.elements.dropRight(1).foldLeft(Path.rootDir)(_ </> Path.dir(_))
    path.elements.lastOption.map(parent </> Path.file(_))
  }

  private def pathToAFileET[F[_]: Monad](path: PrecogPath): EitherT[F, ResourceError, AFile] = {
    pathToAFile(path) match {
      case Some(afile) =>
        EitherT.rightT[F, ResourceError, AFile](afile.point[F])

      case None =>
        EitherT.leftT[F, ResourceError, AFile] {
          val err: ResourceError = ResourceError.notFound(
            s"invalid path does not contain file element: $path")

          err.point[F]
        }
    }
  }

  trait VFSColumnarTableCompanion extends BlockStoreColumnarTableCompanion {

    def load(table: Table, tpe: JType): EitherT[IO, ResourceError, Table] = {
      for {
        _ <- EitherT.rightT(table.toJson.map(json => log.trace("Starting load from " + json.toList.map(_.toJValue.renderCompact))))
        paths <- EitherT.rightT(pathsM(table))

        projections <- paths.toList traverse { path =>
          for {
            _ <- IO(log.debug("Loading path: " + path)).liftM[ET]

            afile <- pathToAFileET[IO](path)

            nihdb <- EitherT.eitherT[IO, ResourceError, NIHDB](openDB(afile).run map { opt =>
              opt.getOrElse(-\/(ResourceError.notFound(s"unable to locate dataset at path $path")))
            })

            projection <- NIHDBProjection.wrap(nihdb).toTask.liftM[ET]
          } yield projection
        }
      } yield {
        val length = projections.map(_.length).sum
        val stream = projections.foldLeft(StreamT.empty[IO, Slice]) { (acc, proj) =>
          // FIXME: Can Schema.flatten return Option[Set[ColumnRef]] instead?
          val constraints = proj.structure.map { struct =>
            Some(Schema.flatten(tpe, struct.toList))
          }

          log.debug("Appending from projection: " + proj)
          acc ++ StreamT.wrapEffect(constraints map { c => proj.getBlockStream(c) })
        }

        Table(stream, ExactSize(length))
      }
    }
  }
}
