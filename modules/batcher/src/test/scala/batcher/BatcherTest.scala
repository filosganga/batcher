/*
 * Copyright 2023 Filippo De Luca
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

package com.filippodeluca.batcher

import scala.concurrent.duration.*

import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*

class BatcherTest extends munit.CatsEffectSuite {

  test("Batcher should return all the deferreds") {
    val batcher = Batcher.resource[IO, String, Int](1, Int.MaxValue, 0.milliseconds) { keys =>
      keys.map(_.size).pure[IO]
    }

    batcher.use { batcher =>
      List(
        batcher("foo"),
        batcher("bar"),
        batcher("baz")
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
        batcher("a"),
        batcher("ab"),
        batcher("abc")
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
        .fill(1000)(batcher("foo"))
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
        batcher("a").attempt,
        batcher("ab").attempt,
        batcher("abc").attempt
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
      val batcher = Batcher.resource[IO, String, Int](1, Int.MaxValue, 50.milliseconds) { keys =>
        count.update(_ + 1).as(keys.map(_.size))
      }

      TestControl.executeEmbed(batcher.use { batcher =>
        List(
          batcher("foo"),
          batcher("bar"),
          batcher("baz")
        ).parSequence
      }) >> count.get.assertEquals(1)
    }

  }

  test("Batcher should run the function multiple time over the linger time") {

    Ref[IO].of(0).flatMap { count =>
      val batcher = Batcher.resource[IO, String, Int](1, Int.MaxValue, 50.milliseconds) { keys =>
        count.update(_ + 1).as(keys.map(_.size))
      }

      TestControl.executeEmbed(batcher.use { batcher =>
        List(
          batcher("foo"),
          batcher("bar"),
          batcher("baz").delayBy(75.milliseconds)
        ).parSequence
      }) >> count.get.assertEquals(2)
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

      TestControl.executeEmbed(batcher.use { batcher =>
        (0 until 100)
          .map { index =>
            batcher(s"test-$index")
          }
          .toList
          .parSequence
      }) >> count.get.map(_._2).assertEquals(maxConcurrency)
    }
  }
}
