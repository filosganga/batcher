# Batcher Library

In a distributed system, it is often necessary to handle multiple concurrent requests for the same or similar resources. However, processing each request individually can lead to unnecessary overhead, especially if the requests are disjointed and do not benefit from sharing resources or operations. Additionally, in certain scenarios, the system may experience spikes of traffic that can overwhelm the available resources and cause performance issues.

The Batcher library aims to address these issues by providing a mechanism to batch similar requests together, reducing the overall number of operations and improving performance. The library offers a simple API for processing requests in batches and allows developers to configure the batch size, maximum concurrency, and the duration to wait for additional requests before processing a batch.

By batching requests, the library can reduce the number of operations needed to process requests and optimize resource utilization. This is particularly useful in scenarios where the system receives many small, disjointed requests, such as HTTP APIs. The Batcher library provides a solution to these problems by enabling developers to improve the performance of their distributed systems while keeping the code simple and concise.

## Features

- Batching requests: The library allows you to batch together requests of the same type, improving efficiency by reducing the overhead of making individual requests.

- Concurrency control: You can specify the maximum number of concurrent requests that can be executed, which allows you to control the load on the system.

- Lingering: The library allows you to specify a duration for how long the Batcher should wait before sending a batch of requests to the server. This can help reduce the number of unnecessary requests by allowing time for other requests to be added to the batch.

- Result caching: The library caches the results of in-flight requests, so if the same request is made again, it can be returned immediately without needing to execute the request again.

It is available in Scala-JS, Scala-Native, and Scala (2.12, 2.13, 3) via Maven Central. 

## Installation
To use the Batcher library in your project, add the following dependency to your `build.sbt` file:

````sbt
libraryDependencies += "com.filippodeluca" %%% "batcher" % "<latest-version>"
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
import scala.concurrent.duration.*

import cats.effect.*
import cats.syntax.all.*
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

There is also an [integration test based on DynamoDb](https://github.com/filosganga/batcher/blob/154c81f09d2f71cf8c3e7c064415ec25a2df96e6/modules/batcher/src/main/scala/batcher/Batcher.scala), based on a real-world use case, that provides a practical demonstration of how to utilize the AWS SDK v2 to efficiently handle GetItem and PutItem requests together, which can be particularly useful in real-world scenarios with high data volumes. By utilizing DynamoDb as the underlying storage engine, the test also highlights the benefits of leveraging cloud-based services for scalable and performant data processing. 