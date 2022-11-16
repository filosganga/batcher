package com.filippodeluca.batcher

import cats.effect._
import cats.syntax.all._

class BatcherTest extends munit.CatsEffectSuite {

  test("Batcher should return all the deferreds") {
    val batcher = Batcher.resource[IO, String, Int] { keys =>
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
    val batcher = Batcher.resource[IO, String, Int] { keys =>
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
    val batcher = Batcher.resource[IO, String, Int] { keys =>
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
    val batcher = Batcher.resource[IO, String, Int] { keys =>
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
}
