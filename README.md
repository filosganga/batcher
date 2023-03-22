# Batcher Library

The Batcher library provides a convenient way to optimize the performance of requests by batching them together, controlling concurrency, and consistently handling errors.

It is available in Scala-JS, Scala-Native, and Scala via Maven Central.

## Features

- Batching requests: The library allows you to batch together requests of the same type, improving efficiency by reducing the overhead of making individual requests.

- Concurrency control: You can specify the maximum number of concurrent requests that can be executed, which allows you to control the load on the system.

- Lingering: The library allows you to specify a duration for how long the batcher should wait before sending a batch of requests to the server. This can help reduce the number of unnecessary requests by allowing time for other requests to be added to the batch.

- Result caching: The library caches the results of in-flight requests, so if the same request is made again, it can be returned immediately without needing to execute the request again.


## Installation
To use the Batcher library in your project, add the following dependency to your `build.sbt` file:

````sbt
libraryDependencies += "com.filippodeluca" %% "batcher" % "<latest-version>"
````

Replace `latest-version` with the latest version of the library available on Maven Central.

## Usage

To use Batcher, you need to create an instance of the `Batcher` trait, which has a single method:

```scala
trait Batcher[F[_], K, V] {
  def single(key: K): F[V]
}
```

This method takes a key of type `K` and returns a `F[V]`, where `F` is some effect type, like IO.

To create a `Batcher` instance, you can use the following method in the `Batcher` companion object:

````scala
def resource[F[_]: Async, K, V](
      maxConcurrency: Int,
      maxBatchSize: Int,
      linger: FiniteDuration
  )(f: IndexedSeq[K] => F[IndexedSeq[V]]): Resource[F, Batcher[F, K, V]]
````

This method takes three parameters:

- `maxConcurrency`: The maximum number of concurrent instances of `f` that can be run in parallel.
- `maxBatchSize`: The maximum number of requests to collect before calling `f`.
- `linger`: The amount of time to wait before calling `f` if the batch hasn't been filled yet.


The fourth parameter, `f`, is a function that takes an `IndexedSeq[K]` of keys and returns an `F[IndexedSeq[V]]` of results in the same order.

## Example
Here's an example of how to use Batcher:

````scala
import scala.concurrent.duration._

import cats.effect._
import cats.syntax.all._
import cats.effect.std.SecureRandom
import cats.effect.std.Console
import java.util.concurrent.atomic.AtomicInteger

object Example extends IOApp.Simple {

  val counter = new AtomicInteger(0)
  class SumApi {
    def batched(requests: Vector[(Int, Int)]): IO[Vector[Int]] = {
      requests
        .traverse { case (l, r) =>
          (l + r).pure[IO]
        }
        .delayBy(750.milliseconds)
    }
  }

  override def run = {

    val api = new SumApi

    val batcher = Batcher.resource[IO, (Int, Int), Int](
      maxConcurrency = 2,
      maxBatchSize = 5,
      linger = 125.milliseconds
    ) { items =>
      api.batched(items.toVector)
    }

    batcher.use { batcher =>
      SecureRandom.javaSecuritySecureRandom[IO].flatMap { random =>
        val fls = Vector.fill(100)(random.betweenInt(0, 10)).sequence
        val frs = Vector.fill(100)(random.betweenInt(0, 10)).sequence
        (fls, frs)
          .mapN { (ls, rs) =>
            ls.zip(rs)
          }
          .flatMap { pairs =>
            pairs
              .parTraverse_ { pair =>
                batcher.single(pair).flatMap { result =>
                  IO.println(s"${pair._1} + ${pair._2} = $result")
                }
              }
          }
      }
    }
  }
}

````

This example first generates two vectors of 100 random integers. Then, it pairs up the corresponding elements of both vectors using the zip method and passes them to the `Batcher.single` method, which sums them up. Finally, it prints the result.
