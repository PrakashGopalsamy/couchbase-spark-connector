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

import com.couchbase.client.scala.codec.JsonDeserializer.Passthrough
import com.couchbase.spark.config.{CouchbaseConfig, CouchbaseConnection, CouchbaseConnectionPool, DSConfigOptions}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.read.PartitionReader
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType
import org.apache.spark.unsafe.types.UTF8String
import com.couchbase.client.scala.query.{QueryMetrics, QueryScanConsistency, QueryOptions => CouchbaseQueryOptions}
import com.couchbase.spark.DefaultConstants
import com.couchbase.spark.json.CouchbaseJsonUtils
import org.apache.spark.sql.connector.expressions.aggregate.Aggregation
import org.apache.spark.sql.connector.metric.CustomTaskMetric

import scala.concurrent.duration.Duration

class QueryPartitionReader(schema: StructType, conf: CouchbaseConfig, filters: Array[Filter],
                           aggregations: Option[Aggregation])
  extends PartitionReader[InternalRow]
  with Logging {

  private val scopeName = conf.dsConfig.scope.getOrElse(DefaultConstants.DefaultScopeName)
  private val collectionName = conf.dsConfig.collection.getOrElse(DefaultConstants.DefaultCollectionName)

  private val parser = CouchbaseJsonUtils.jsonParser(schema)
  private val createParser = CouchbaseJsonUtils.createParser()

  private var resultMetrics: Option[QueryMetrics] = None

  private val groupByColumns = aggregations match {
    case Some(agg) => agg.groupByColumns().map(n => n.fieldNames().head).toSeq
    case None => Seq.empty
  }

  private lazy val result = {
    if (scopeName.equals(DefaultConstants.DefaultScopeName) && collectionName.equals(DefaultConstants.DefaultCollectionName)) {
      CouchbaseConnectionPool().getConnection(conf).cluster().query(buildQuery(), buildOptions())
    } else {
      CouchbaseConnectionPool().getConnection(conf).cluster().bucket(conf.dsConfig.bucket).scope(scopeName).query(buildQuery(), buildOptions())
    }
  }

  private lazy val rows = result
    .flatMap(result => {
      resultMetrics = result.metaData.metrics
      result.rowsAs[String](Passthrough.StringConvert)
    })
    .get
    .flatMap(r => {
      var row = r
      try {
        // Aggregates like MIN, MAX etc will end up as $1, $2 .. so they need to be replaced
        // with their original field names from the schema so that the JSON parser can pick
        // them up properly
        if (hasAggregateFields) {
          var idx = 1
          schema.fields.foreach(field => {
            if (!groupByColumns.contains(field.name)) {
              row = row.replace("$" + idx, field.name)
              idx = idx + 1
            }
          })
        }
        parser.parse(row, createParser, UTF8String.fromString)
      } catch {
        case e: Exception =>
          throw new IllegalStateException(s"Could not parse row $row based on provided schema $schema.", e)
      }
    })

  private lazy val rowIterator = rows.iterator
  protected var lastRow: InternalRow = InternalRow()

  def buildQuery(): String = {
    var fields = schema
      .fields
      .map(f => f.name)
      .filter(f => !f.equals(conf.dsConfig.idFieldName))
      .map(f => maybeEscapeField(f))
    if (!hasAggregateFields) {
      fields = fields :+ s"META().id as `${conf.dsConfig.idFieldName}`"
    }

    var predicate = conf.dsConfig.userFilter.map(p => s" WHERE $p").getOrElse("")
    val compiledFilters = N1qlFilters.compile(filters)
    if (compiledFilters.nonEmpty && predicate.nonEmpty) {
      predicate = predicate + " AND " + compiledFilters
    } else if (compiledFilters.nonEmpty) {
      predicate = " WHERE " + compiledFilters
    }

    val groupBy = if (hasAggregateGroupBy) {
      " GROUP BY " + aggregations.get.groupByColumns().map(n => s"`${n.fieldNames().head}`").mkString(", ")
    } else {
      ""
    }

    val fieldsEncoded = fields.mkString(", ")

    val query = if (scopeName.equals(DefaultConstants.DefaultScopeName) && collectionName.equals(DefaultConstants.DefaultCollectionName)) {
      s"select $fieldsEncoded from `${conf.dsConfig.bucket}`$predicate$groupBy"
    } else {
      s"select $fieldsEncoded from `$collectionName`$predicate$groupBy"
    }

    logDebug(s"Building and running N1QL query $query")
    query
  }

  def hasAggregateFields: Boolean = {
    aggregations match {
      case Some(a) => !a.aggregateExpressions().isEmpty
      case None => false
    }
  }

  def hasAggregateGroupBy: Boolean = {
    aggregations match {
      case Some(a) => !a.groupByColumns().isEmpty
      case None => false
    }
  }

  def maybeEscapeField(field: String): String = {
    if (field.startsWith("MAX")
      || field.startsWith("MIN")
      || field.startsWith("COUNT")
      || field.startsWith("SUM")
      || field.startsWith("`")) {
      field
    } else {
      s"`$field`"
    }
  }

  def buildOptions(): CouchbaseQueryOptions = {
    var opts = CouchbaseQueryOptions().metrics(true)
    conf.dsConfig.scanConsistency match {
      case DSConfigOptions.NotBoundedScanConsistency => opts = opts.scanConsistency(QueryScanConsistency.NotBounded)
      case DSConfigOptions.RequestPlusScanConsistency => opts = opts.scanConsistency(QueryScanConsistency.RequestPlus())
      case v => throw new IllegalArgumentException("Unknown scanConsistency of " + v)
    }
    conf.dsConfig.timeout.foreach(t => opts = opts.timeout(Duration(t)))
    opts
  }

  override def next(): Boolean = {
    if (rowIterator.hasNext) {
      lastRow = rowIterator.next()
      true
    } else {
      false
    }
  }

  override def get(): InternalRow = lastRow
  override def close(): Unit = {}

  override def currentMetricsValues(): Array[CustomTaskMetric] = {
    resultMetrics match {
      case Some(m) =>
        Array(
          new CustomTaskMetric {
            override def name(): String = "elapsedTimeMs"
            override def value(): Long = m.elapsedTime.toMillis
          },
          new CustomTaskMetric {
            override def name(): String = "executionTimeMs"
            override def value(): Long = m.executionTime.toMillis
          },
          new CustomTaskMetric {
            override def name(): String = "sortCount"
            override def value(): Long = m.sortCount
          },
          new CustomTaskMetric {
            override def name(): String = "errorCount"
            override def value(): Long = m.errorCount
          },
          new CustomTaskMetric {
            override def name(): String = "resultSize"
            override def value(): Long = m.resultSize
          },
          new CustomTaskMetric {
            override def name(): String = "mutationCount"
            override def value(): Long = m.mutationCount
          },
          new CustomTaskMetric {
            override def name(): String = "resultCount"
            override def value(): Long = m.resultCount
          },
          new CustomTaskMetric {
            override def name(): String = "warningCount"
            override def value(): Long = m.warningCount
          },
        )
      case None => Array()
    }
  }
}
