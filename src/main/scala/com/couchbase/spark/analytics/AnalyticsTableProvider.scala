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
package com.couchbase.spark.analytics

import com.couchbase.spark.DefaultConstants
import com.couchbase.spark.config._
import com.couchbase.client.scala.analytics.{AnalyticsScanConsistency, AnalyticsOptions => CouchbaseAnalyticsOptions}
import com.couchbase.client.scala.codec.JsonDeserializer.Passthrough
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{Encoders, SparkSession}
import org.apache.spark.sql.connector.catalog.{Table, TableProvider}
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.sources.DataSourceRegister
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

import java.util

class AnalyticsTableProvider extends TableProvider with Logging with DataSourceRegister {

  override def shortName(): String = "couchbase.analytics"

  private lazy val sparkSession = SparkSession.active

  override def inferSchema(options: CaseInsensitiveStringMap): StructType = {
    val conf = CouchbaseConfig(sparkSession.sparkContext.getConf,false).loadDSOptions(options.asCaseSensitiveMap())
    val idFieldName = Option(options.get(DSConfigOptions.IdFieldName)).getOrElse(DefaultConstants.DefaultIdFieldName)
    val whereClause = Option(options.get(DSConfigOptions.Filter)).map(p => s" WHERE $p").getOrElse("")
    val dataset = options.get(DSConfigOptions.Dataset)
    val inferLimit = Option(options.get(DSConfigOptions.InferLimit)).getOrElse(DefaultConstants.DefaultInferLimit)

    val scanConsistency = Option(options.get(DSConfigOptions.ScanConsistency))
      .getOrElse(DefaultConstants.DefaultAnalyticsScanConsistency)

    val opts = CouchbaseAnalyticsOptions()
    scanConsistency match {
      case DSConfigOptions.NotBoundedScanConsistency => opts.scanConsistency(AnalyticsScanConsistency.NotBounded)
      case DSConfigOptions.RequestPlusScanConsistency => opts.scanConsistency(AnalyticsScanConsistency.RequestPlus)
      case v => throw new IllegalArgumentException("Unknown scanConsistency of " + v)
    }

    val bucketName = conf.bucketName
    val scopeName = conf.scopeName

    val result = if (bucketName.isEmpty || scopeName.isEmpty) {
      val statement = s"SELECT META().id as $idFieldName, `$dataset`.* FROM `$dataset`$whereClause LIMIT $inferLimit"
      logDebug(s"Inferring schema from bucket $dataset with query '$statement'")
      CouchbaseConnectionPool().getConnection(conf).cluster().analyticsQuery(statement, opts)
    } else {
      val statement = s"SELECT META().id as $idFieldName, `$dataset`.* FROM `$dataset`$whereClause LIMIT $inferLimit"
      logDebug(s"Inferring schema from bucket/scope/dataset $bucketName/$scopeName/$dataset with query '$statement'")
      CouchbaseConnectionPool().getConnection(conf).cluster().bucket(bucketName.get).scope(scopeName.get).analyticsQuery(statement, opts)
    }

    val rows = result.flatMap(result => result.rowsAs[String](Passthrough.StringConvert)).get
    val ds = sparkSession.sqlContext.createDataset(rows)(Encoders.STRING)
    val schema = sparkSession.sqlContext.read.json(ds).schema

    logDebug(s"Inferred schema is $schema")

    schema
  }

  def validateConfig(couchbaseConfig: CouchbaseConfig): CouchbaseConfig = {
    val dataset = couchbaseConfig.dsConfig.dataset
    if (dataset == null) {
      throw new IllegalArgumentException("A dataset must be provided through the options!")
    }
    couchbaseConfig
  }

  /**
   * Returns the "Table", either with an inferred schema or a user provide schema.
   *
   * @param schema the schema, either inferred or provided by the user.
   * @param partitioning partitioning information.
   * @param properties the properties for customization
   * @return the table instance which performs the actual work inside it.
   */
  override def getTable(schema: StructType, partitioning: Array[Transform], properties: util.Map[String, String]): Table =
    new AnalyticsTable(schema, partitioning, properties, validateConfig(CouchbaseConfig(sparkSession.sparkContext.getConf,false).loadDSOptions(properties)))

  /**
   * We allow a user passing in a custom schema.
   */
  override def supportsExternalMetadata(): Boolean = true


}
