package com.filippodeluca.batcher

import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._

trait Batcher[F[_], K, V] {
  def single(key: K): F[V]
}

object Batcher {

  def resource[F[_]: Concurrent, K, V](f: IndexedSeq[K] => F[IndexedSeq[V]]) = {

    Resource
      .eval(Ref[F].of(Map.empty[K, Deferred[F, Either[Throwable, V]]]))
      .flatMap { loading =>

        val tick: F[Unit] = loading
          .getAndSet(Map.empty)
          .flatMap { loading =>
            if (loading.isEmpty) {
              ().pure[F]
            } else {
              val (keys, deferreds) =
                loading.toVector.foldMap(x => Vector(x._1) -> Vector(x._2))
              f(keys).attempt.flatMap {
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

        tick.foreverM.background.as(loading)
      }
      .map { loading =>
        new Batcher[F, K, V] {

          def single(key: K): F[V] = {
            Deferred[F, Either[Throwable, V]].flatMap { deferred =>
              loading
                .modify { loading =>
                  loading
                    .get(key)
                    .fold {
                      loading.updated(key, deferred) -> deferred
                    } { deferred =>
                      loading -> deferred
                    }
                }
                .flatMap { deferred =>
                  deferred.get.rethrow
                }
            }
          }
        }
      }
  }

}
