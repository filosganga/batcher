package com.filippodeluca.batcher
package dynamodb

import java.net.URI

import scala.concurrent.duration._

import cats.data.Chain
import cats.syntax.all._
import cats.effect._
import cats.effect.std.{Env, UUIDGen}
import fs2._

import software.amazon.awssdk.services.dynamodb.model.{BatchGetItemRequest, BatchWriteItemRequest}
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{
  KeysAndAttributes,
  AttributeValue,
  AttributeDefinition,
  KeySchemaElement,
  TableDescription,
  ScalarAttributeType,
  KeyType,
  WriteRequest,
  BillingMode
}

import JdkConverters._
import MapCompat._

class BatchGetSuite extends munit.CatsEffectSuite {

  val dynamoDbEndpoint = Env[IO].get("DYNAMODB_ENDPOINT").flatMap {
    case Some(endpoint) => IO(URI.create(endpoint))
    case None =>
      IO.raiseError(new RuntimeException("DYNAMODB_ENDPOINT environment variable not found"))
  }

  val dynamoDbClientR: Resource[IO, DynamoDbAsyncClient] = Resource.fromAutoCloseable {
    dynamoDbEndpoint.flatMap { endpoint =>
      IO(
        DynamoDbAsyncClient
          .builder()
          .endpointOverride(endpoint)
          .build()
      )
    }
  }

  def tableR(
      dynamoDbClient: DynamoDbAsyncClient,
      attributeDefinitions: List[AttributeDefinition],
      keySchema: List[KeySchemaElement]
  ): Resource[IO, TableDescription] = {
    Resource.eval(UUIDGen.randomUUID[IO]).map(_.toString).map(id => s"table-$id").flatMap {
      tableName =>
        Resource.make {
          IO.fromCompletableFuture(
            IO(
              dynamoDbClient.createTable(
                _.tableName(tableName)
                  .billingMode(BillingMode.PAY_PER_REQUEST)
                  .attributeDefinitions(attributeDefinitions: _*)
                  .keySchema(keySchema: _*)
              )
            )
          ).map { response =>
            response.tableDescription
          }
        } { td =>
          IO.fromCompletableFuture(IO(dynamoDbClient.deleteTable(_.tableName(td.tableName)))).void
        }

    }
  }

  case class PutItem(table: String, item: Map[String, AttributeValue])
  case class GetItem(table: String, key: Map[String, AttributeValue])

  def batchGetItemBatcher(
      dynamoDbClient: DynamoDbAsyncClient,
      tablesKeys: Map[String, List[String]]
  ): Resource[IO, Batcher[IO, GetItem, Option[Map[String, AttributeValue]]]] = {
    Batcher.resource[IO, GetItem, Option[Map[String, AttributeValue]]](16, 100, 50.milliseconds) {
      getItems =>

        val requestItems = getItems
          .groupBy(_.table)
          .map { case (table, getItems) =>
            val keys = getItems.map { getItem =>
              getItem.key.asJava
            }

            table -> KeysAndAttributes.builder().keys(keys: _*).build()
          }
          .asJava

        val itemsByGetItem = Stream
          .unfoldLoopEval(BatchGetItemRequest.builder.requestItems(requestItems).build()) {
            request =>
              IO.fromCompletableFuture(IO(dynamoDbClient.batchGetItem(request))).map { response =>
                val responses = response.responses

                val output =
                  Chunk.vector(responses.asScala.toVector.flatMap { case (table, items) =>
                    val tableKeys = tablesKeys.get(table).toList.flatten
                    items.asScala.map { item =>
                      (
                        GetItem(
                          table,
                          item.asScala.view.filterKeys(tableKeys.contains).toMap
                        ),
                        item
                      )
                    }.toVector
                  })

                val nextS =
                  if (response.hasUnprocessedKeys && !response.unprocessedKeys.isEmpty) {
                    request.toBuilder.requestItems(response.unprocessedKeys).build.some
                  } else {
                    none[BatchGetItemRequest]
                  }

                (output, nextS)
              }
          }
          .unchunks
          .compile
          .toVector
          .map(_.toMap)

        itemsByGetItem.map { itemsByGetItem =>
          getItems.map { getItem =>
            itemsByGetItem.get(getItem).map(_.asScala.toMap)
          }
        }
    }
  }

  def batchPutItemBatcher(
      dynamoDbClient: DynamoDbAsyncClient
  ): Resource[IO, Batcher[IO, PutItem, Unit]] = {
    Batcher.resource[IO, PutItem, Unit](16, 25, 50.milliseconds) { putItems =>

      val requestItems = putItems
        .groupBy(_.table)
        .map { case (table, putItems) =>
          val requests = putItems
            .map { putItem =>
              WriteRequest.builder().putRequest(_.item(putItem.item.asJava).build).build()
            }
            .toList
            .asJava

          (table, requests)
        }
        .asJava

      Stream
        .unfoldLoopEval(BatchWriteItemRequest.builder().requestItems(requestItems).build()) {
          request =>
            IO.fromCompletableFuture(IO(dynamoDbClient.batchWriteItem(request))).map { response =>

              val nextS =
                if (response.hasUnprocessedItems && !response.unprocessedItems.isEmpty) {
                  request.toBuilder.requestItems(response.unprocessedItems).build.some
                } else {
                  none[BatchWriteItemRequest]
                }

              ((), nextS)
            }
        }
        .compile
        .drain
        .as(putItems.map(_ => ()))
    }
  }

  ResourceFunFixture {
    for {
      dynamoDbClient <- dynamoDbClientR
      table <- tableR(
        dynamoDbClient,
        List(
          AttributeDefinition
            .builder()
            .attributeName("id")
            .attributeType(ScalarAttributeType.S)
            .build()
        ),
        List(
          KeySchemaElement
            .builder()
            .attributeName("id")
            .keyType(KeyType.HASH)
            .build()
        )
      )
      getItemBatcher <- batchGetItemBatcher(dynamoDbClient, Map(table.tableName -> List("id")))
      putItemBatcher <- batchPutItemBatcher(dynamoDbClient)
    } yield (table, getItemBatcher, putItemBatcher)
  }.test("should be able to store and retrieve items from one table") {
    case (table, getItemBatcher, putItemBatcher) =>
      val items = List.range(0, 1000).map { idx =>
        Map(
          "id" -> AttributeValue.builder.s(s"test-$idx").build,
          "value" -> AttributeValue.builder.n(idx.toString).build
        )
      }

      val expectedItems = items.map(_.some).toSet

      val (putItems, getItems) = items.foldMap { item =>

        val putItem = Chain.one(
          PutItem(
            table.tableName,
            item
          )
        )

        val getItem = Chain.one(
          GetItem(
            table.tableName,
            item.view.filterKeys(_ == "id").toMap
          )
        )

        (putItem, getItem)
      }

      putItems.parTraverse(putItemBatcher.single) *> getItems
        .parTraverse(getItemBatcher.single)
        .map(_.toList.toSet)
        .assertEquals(expectedItems)
  }

}
