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
import com.couchbase.spark.kv.Upsert
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotNull, assertThrows}
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.{AfterAll, BeforeAll, Test, TestInstance}
import org.testcontainers.couchbase.{BucketDefinition, CouchbaseContainer}

import java.util.UUID

@TestInstance(Lifecycle.PER_CLASS)
class QueryDataFrameIntegrationTest {

  var container: CouchbaseContainer = _
  var spark: SparkSession = _

  @BeforeAll
  def setup(): Unit = {
    val bucketName: String = UUID.randomUUID().toString

    container = new CouchbaseContainer("couchbase/server:6.6.2")
      .withBucket(new BucketDefinition(bucketName).withPrimaryIndex(true))
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
      JsonObject.fromJson("""{"type":"airport","airportname": "Peronne St Quentin"}""")
    )
    val airport_1258 = Upsert(
      "airport_1258",
      JsonObject.fromJson("""{"type":"airport","airportname": "Bray"}""")
    )

    val airline_10748 = Upsert(
      "airline_10748",
      JsonObject.fromJson("""{"type":"airline","name":"Locair"}""")
    )

    val airline_10765 = Upsert(
      "airline_10765",
      JsonObject.fromJson("""{"type":"airline","name":"SeaPort Airlines"}""")
    )

    spark
      .sparkContext
      .couchbaseUpsert(Seq(airport_1255, airport_1258, airline_10748, airline_10765))
      .collect()
  }

  @Test
  def readsDocumentsWithFilter(): Unit = {
    val airports = spark.read
      .format("couchbase.query")
      .option(QueryOptions.Filter, "type = 'airport'")
      .option(QueryOptions.ScanConsistency, QueryOptions.RequestPlusScanConsistency)
      .load()

    assertEquals(2, airports.count)
    airports.foreach(row => {
      assertEquals("airport", row.getAs[String]("type"))
      assertNotNull(row.getAs[String]("__META_ID"))
      assertNotNull(row.getAs[String]("airportname"))
    })
  }

  @Test
  def canChangeIdFieldName(): Unit = {
    val airports = spark.read
      .format("couchbase.query")
      .option(QueryOptions.Filter, "type = 'airport'")
      .option(QueryOptions.IdFieldName, "myIdFieldName")
      .option(QueryOptions.ScanConsistency, QueryOptions.RequestPlusScanConsistency)
      .load()

    airports.foreach(row => {
      assertEquals("airport", row.getAs[String]("type"))
      assertThrows(classOf[IllegalArgumentException], () => row.getAs[String]("__META_ID"))
      assertNotNull(row.getAs[String]("myIdFieldName"))
    })
  }

}
