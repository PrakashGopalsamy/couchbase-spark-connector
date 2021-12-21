/*
 * Copyright (c) 2021 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.spark.query

import com.couchbase.client.scala.json.JsonObject
import com.couchbase.client.scala.manager.collection.CollectionSpec
import com.couchbase.spark.config.{CouchbaseConfig, CouchbaseConnection}
import com.couchbase.spark.kv.Upsert
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotNull, assertThrows}
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.{AfterAll, BeforeAll, Test, TestInstance}
import org.testcontainers.couchbase.{BucketDefinition, CouchbaseContainer}

import java.util.UUID

@TestInstance(Lifecycle.PER_CLASS)
class QueryDataFrameCollectionsIntegrationTest {

  var container: CouchbaseContainer = _
  var spark: SparkSession = _

  private val bucketName = UUID.randomUUID().toString
  private val scopeName = UUID.randomUUID().toString
  private val airportCollectionName = UUID.randomUUID().toString
  private val airlineCollectionName = UUID.randomUUID().toString

  @BeforeAll
  def setup(): Unit = {
    container = new CouchbaseContainer("couchbase/server:7.0.3")
      .withBucket(new BucketDefinition(bucketName))
    container.start()

    spark = SparkSession
      .builder()
      .master("local[*]")
      .appName(this.getClass.getSimpleName)
      .config("spark.couchbase.connectionString", container.getConnectionString)
      .config("spark.couchbase.username", container.getUsername)
      .config("spark.couchbase.password", container.getPassword)
      .config("spark.couchbase.implicitBucket", bucketName)
      .getOrCreate()

    val bucket = CouchbaseConnection().bucket(CouchbaseConfig(spark.sparkContext.getConf), Some(bucketName))

    bucket.collections.createScope(scopeName)
    bucket.collections.createCollection(CollectionSpec(airportCollectionName, scopeName))
    bucket.collections.createCollection(CollectionSpec(airlineCollectionName, scopeName))

    bucket.scope(scopeName).query(s"create primary index on `$airportCollectionName`")
    bucket.scope(scopeName).query(s"create primary index on `$airlineCollectionName`")

    prepareSampleData()
  }

  @AfterAll
  def teardown(): Unit = {
    container.stop()
  }

  private def prepareSampleData() = {
    import com.couchbase.spark._

    val airport_1255 = Upsert(
      "airport_1255",
      JsonObject.fromJson("""{"airportname": "Peronne St Quentin"}""")
    )
    val airport_1258 = Upsert(
      "airport_1258",
      JsonObject.fromJson("""{"airportname": "Bray"}""")
    )

    val airline_10748 = Upsert(
      "airline_10748",
      JsonObject.fromJson("""{"name":"Locair"}""")
    )

    val airline_10765 = Upsert(
      "airline_10765",
      JsonObject.fromJson("""{"name":"SeaPort Airlines"}""")
    )

    spark
      .sparkContext
      .couchbaseUpsert(Seq(airport_1255, airport_1258), Keyspace(scope = Some(scopeName), collection = Some(airportCollectionName)))
      .collect()

    spark
      .sparkContext
      .couchbaseUpsert(Seq(airline_10748, airline_10765), Keyspace(scope = Some(scopeName), collection = Some(airlineCollectionName)))
      .collect()
  }

  @Test
  def readsDocumentsWithFilter(): Unit = {
    val airports = spark.read
      .format("couchbase.query")
      .option(QueryOptions.Scope, scopeName)
      .option(QueryOptions.Collection, airportCollectionName)
      .option(QueryOptions.ScanConsistency, QueryOptions.RequestPlusScanConsistency)
      .load()

    assertEquals(2, airports.count)
    airports.foreach(row => {
      assertNotNull(row.getAs[String]("__META_ID"))
      assertNotNull(row.getAs[String]("airportname"))
    })
  }

  @Test
  def canChangeIdFieldName(): Unit = {
    val airports = spark.read
      .format("couchbase.query")
      .option(QueryOptions.IdFieldName, "myIdFieldName")
      .option(QueryOptions.Scope, scopeName)
      .option(QueryOptions.Collection, airportCollectionName)
      .option(QueryOptions.ScanConsistency, QueryOptions.RequestPlusScanConsistency)
      .load()

    airports.foreach(row => {
      assertThrows(classOf[IllegalArgumentException], () => row.getAs[String]("__META_ID"))
      assertNotNull(row.getAs[String]("myIdFieldName"))
    })
  }


}
