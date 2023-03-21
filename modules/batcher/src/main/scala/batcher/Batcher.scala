package com.filippodeluca.batcher

import scala.concurrent.duration._

import cats.Applicative
import cats.effect._
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2._

trait Batcher[F[_], K, V] {
  def single(key: K): F[V]
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

          def single(key: K): F[V] = {
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
