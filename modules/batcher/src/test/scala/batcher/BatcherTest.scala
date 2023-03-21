package com.filippodeluca.batcher

import scala.concurrent.duration._

import cats.effect._
import cats.syntax.all._

class BatcherTest extends munit.CatsEffectSuite {

  test("Batcher should return all the deferreds") {
    val batcher = Batcher.resource[IO, String, Int](1, Int.MaxValue, 0.milliseconds) { keys =>
      keys.map(_.size).pure[IO]
    }

    batcher.use { batcher =>
      List(
        batcher.single("foo"),
        batcher.single("bar"),
        batcher.single("baz")
      ).parTraverse(identity)
        .map { xs =>
          xs.map(x => assertEquals(x, 3))
        }
    }
  }

  test("Batcher should return all the deferred in order") {
    val batcher = Batcher.resource[IO, String, Int](1, Int.MaxValue, 0.milliseconds) { keys =>
      keys.map(_.size).pure[IO]
    }

    batcher.use { batcher =>
      List(
        batcher.single("a"),
        batcher.single("ab"),
        batcher.single("abc")
      ).parTraverse(identity)
        .map { xs =>
          xs.zipWithIndex.map { case (x, y) =>
            assertEquals(x, y + 1)
          }
        }
    }
  }

  test("Batcher should return n-times the same key") {
    val batcher = Batcher.resource[IO, String, Int](1, Int.MaxValue, 0.milliseconds) { keys =>
      keys.map(_.size).pure[IO]
    }

    batcher.use { batcher =>
      List
        .fill(1000)(batcher.single("foo"))
        .parTraverse(identity)
        .map { xs =>
          xs.map(x => assertEquals(x, 3))
        }
    }
  }

  test("Batcher should fail ALL the requests if the given function fails") {
    val batcher = Batcher.resource[IO, String, Int](1, Int.MaxValue, 0.milliseconds) { _ =>
      IO.raiseError(new java.lang.RuntimeException)
    }

    batcher.use { batcher =>
      val result = List(
        batcher.single("a").attempt,
        batcher.single("ab").attempt,
        batcher.single("abc").attempt
      ).sequence

      result.map { xs =>
        xs.map { x =>
          assert(x.isLeft, x)
        }
      }
    }
  }

  test("Batcher should run the function only once within the linger time") {

    Ref[IO].of(0).flatMap { count =>
      val batcher = Batcher.resource[IO, String, Int](1, Int.MaxValue, 5.milliseconds) { keys =>
        count.update(_ + 1).as(keys.map(_.size))
      }

      batcher.use { batcher =>
        val result = List(
          batcher.single("foo"),
          batcher.single("bar"),
          batcher.single("baz")
        ).parSequence

        result >> count.get.assertEquals(1)
      }
    }

  }

  test("Batcher should run the function multiple time over the linger time") {

    Ref[IO].of(0).flatMap { count =>
      val batcher = Batcher.resource[IO, String, Int](1, Int.MaxValue, 50.milliseconds) { keys =>
        count.update(_ + 1).as(keys.map(_.size))
      }

      batcher.use { batcher =>
        val result = List(
          batcher.single("foo"),
          batcher.single("bar"),
          batcher.single("baz").delayBy(75.milliseconds)
        ).parSequence

        result >> count.get.assertEquals(2)

      }
    }
  }

  test("Batcher should run the function in parallel up to maxConcurrency") {

    val maxConcurrency = 3

    Ref[IO].of((0, 0)).flatMap { count =>
      val batcher = Batcher.resource[IO, String, Int](maxConcurrency, 3, 50.milliseconds) { keys =>

        val increase = count.update { case (curr, max) =>
          val newCurr = curr + 1
          val newMax = Math.max(max, newCurr)
          (newCurr, newMax)
        }

        val decrease = count.update { case (curr, max) =>
          val newCurr = curr - 1
          val newMax = Math.max(max, newCurr)
          (newCurr, newMax)
        }

        (increase *> IO.sleep(50.milliseconds) *> decrease).as(keys.map(_.size))
      }

      batcher.use { batcher =>

        val result = (0 until 100)
          .map { index =>
            batcher.single(s"test-$index")
          }
          .toList
          .parSequence

        result >> count.get.map(_._2).assertEquals(maxConcurrency)

      }
    }
  }
}
