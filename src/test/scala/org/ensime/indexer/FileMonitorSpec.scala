package org.ensime.indexer

import org.scalatest.fixture
import org.scalatest.concurrent.ConductorFixture
import org.scalatest._
import concurrent.AsyncAssertions._
import java.util.concurrent.ArrayBlockingQueue
import java.io.File
import java.nio.file.{Path, Paths, Files}
import org.scalatest.time.SpanSugar._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits._
import time.{Span, Millis, Seconds}

class FileMonitorSpec extends fixture.FunSuite with ConductorFixture
  with Matchers with TempDirFixtures {

  test("FileMonitor should detect a new file") { conductor =>

    import conductor._

    withTempDirs { rootPath =>
      info("watch dir:" + rootPath)
      @volatile var w: Waiter = null

      val watcher = new FileMonitor()
      watcher.addSelector("scala")
      watcher.addWatchedDir(rootPath.toFile)
      watcher.register()
      Files.createTempFile(rootPath, "new-file", ".scala")

      thread {
        w = new Waiter
        val listener = new FileChangeListener {
          def fileAdded(f: File): Unit = {
            info("fileAdded: " + f)
            w { assert(true) }
            w.dismiss()
          }
          def fileChanged(f: File): Unit = {
            info("fiileChanged" + f)
            w { assert(false) }
            w.dismiss()
          }
          def fileRemoved(f: File): Unit = {
            info("fileRemovded" + f)
            w { assert(false) }
            w.dismiss()
          }
        }
        watcher.addListener(listener)

        w.await(timeout(Span(30, Seconds)), dismissals(1))
      }
      thread {
        watcher.watch(1)
      }
      conductor.conduct(timeout(Span(20, Seconds)))
    }
  }

  test("FileMonitor should detect a new file in a new directory") { conductor =>

    import conductor._

    withTempSubdir { (rootPath, subdirPath) =>
      info("watch dir:" + subdirPath)
      @volatile var w: Waiter = null

      val watcher = new FileMonitor()
      watcher.addSelector("scala")
      watcher.addWatchedDir(rootPath.toFile)
      watcher.register()

      thread {
        w = new Waiter
        val listener = new FileChangeListener {
          def fileAdded(f: File): Unit = {
            info("fileAdded:" + f)
            //Files.createTempFile(sub, "new", ".scala")
            w { assert(true) }
            w.dismiss()
          }
          def fileChanged(f: File): Unit = {
            info("fileChanged:" + f)
            w { assert(false) }
            w.dismiss()
          }
          def fileRemoved(f: File): Unit = {
            info("fileRemoved:" + f)
            w { assert(false) }
            w.dismiss()
            w.dismiss()
            w.dismiss()
          }

        }
        watcher.addListener(listener)
        waitForBeat(2)
        beat should be(2)
        info("Create a new dir.")
        val sub: Path = Files.createTempDirectory(rootPath, "sub")
        waitForBeat(12)
        info("Give at least 10 secs to detect and register a new dir.")
        info("Create a file in a new dir.")
        Files.createTempFile(rootPath, "new", ".scala")
        w.await(timeout(Span(25, Seconds)), dismissals(1))
      }

      thread {
        waitForBeat(1)
        beat should be(1)
        info("Start watching.")
        watcher.watch(2)
      }

      conductor.conduct(timeout(Span(1, Seconds)), interval(Span(1, Seconds)))
    }
  }

}

trait TempDirFixtures {
  def withTempDirs(testCode: Path => Any): Any = {
    val root1: Path = Files.createTempDirectory("root")
    val sub11: Path = Files.createTempDirectory(root1, "sub1")
    val tempFile111: Path = Files.createTempFile(sub11, "file", ".tmp");
    val sub12: Path = Files.createTempDirectory(root1, "sub2")
    testCode(root1)
    // Files.delete(sub11)
    // Files.delete(sub12)
    // Files.delete(root1)
  }
  def withTempSubdir(testCode: (Path, Path) => Any): Any = {
    val root1: Path = Files.createTempDirectory("root")
    val sub11: Path = Files.createTempDirectory(root1, "sub1")
    //val tempFile111: Path = Files.createTempFile(sub11, "file", ".tmp");
    val sub12: Path = Files.createTempDirectory(root1, "sub2")
    testCode(root1, sub11)
    // Files.delete(sub11)
    // Files.delete(sub12)
    // Files.delete(root1)
  }

}
