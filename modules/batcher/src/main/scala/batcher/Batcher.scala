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

import cats.Applicative
import cats.effect.*
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.*

trait Batcher[F[_], K, V] {
  def apply(key: K): F[V]
}

object Batcher {

  def resource[F[_]: Async, K, V](
      maxConcurrency: Int,
      maxBatchSize: Int,
      linger: FiniteDuration
  )(f: IndexedSeq[K] => F[IndexedSeq[V]]): Resource[F, Batcher[F, K, V]] = {

    case class Resources(
        cache: Ref[F, Map[K, Deferred[F, Either[Throwable, V]]]],
        queue: Queue[F, K]
    )

    case class Outcome(deferred: Deferred[F, Either[Throwable, V]], isNew: Boolean)

    Resource
      .eval(Ref[F].of(Map.empty[K, Deferred[F, Either[Throwable, V]]]))
      .flatMap { cache =>
        Resource.eval(Queue.unbounded[F, K]).flatMap { queue =>
          val stream: Stream[F, Unit] = Stream
            .fromQueueUnterminated(queue, maxBatchSize)
            .groupWithin(maxBatchSize, linger)
            .mapAsync(maxConcurrency) { chunk =>
              val xs = chunk.toVector
              f(xs).attempt.flatMap { results =>
                cache
                  .modify { map =>
                    (
                      map -- xs,
                      xs.map { k =>
                        map(k)
                      }
                    )
                  }
                  .flatMap { deferreds =>
                    results match {
                      case Right(results) =>
                        deferreds
                          .zip(results)
                          .traverse { case (a, b) => a.complete(Right(b)) }
                          .void
                      case Left(err) =>
                        deferreds.traverse(deferred => deferred.complete(Left(err))).void

                    }
                  }
              }
            }

          stream.compile.drain.background.as(Resources(cache, queue))
        }
      }
      .map { resources =>
        new Batcher[F, K, V] {

          def apply(key: K): F[V] = {
            Deferred[F, Either[Throwable, V]].flatMap { newDeferred =>
              resources.cache
                .modify { loading =>
                  loading
                    .get(key)
                    .fold {
                      (loading.updated(key, newDeferred), Outcome(newDeferred, true))
                    } { deferred =>
                      (loading, Outcome(deferred, false))
                    }
                }
                .flatMap { outcome =>
                  val appendIfNeeded = if (outcome.isNew) {
                    resources.queue.offer(key)
                  } else {
                    Applicative[F].unit
                  }

                  appendIfNeeded >> outcome.deferred.get.rethrow
                }
            }
          }
        }

      }
  }

}
