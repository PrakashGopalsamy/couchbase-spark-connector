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

package com.couchbase.spark.config

import com.couchbase.client.core.env.{AbstractMapPropertyLoader, CoreEnvironment, PropertyLoader}
import com.couchbase.client.core.error.InvalidArgumentException
import com.couchbase.client.core.io.CollectionIdentifier
import com.couchbase.client.core.transaction.config.CoreTransactionsCleanupConfig
import com.couchbase.client.core.util.ConnectionString
import com.couchbase.client.scala.{Bucket, Cluster, ClusterOptions, Collection, Scope}
import com.couchbase.client.scala.env.{ClusterEnvironment, SecurityConfig}
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.apache.spark.internal.Logging

import java.nio.file.Paths
import java.util
import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt}
import scala.collection.JavaConverters._

class CouchbaseConnection(cfg: CouchbaseConfig) extends Serializable with Logging {

  @transient var envRef: Option[ClusterEnvironment] = None

  @transient var clusterRef: Option[Cluster] = None

  @transient var bucketsRef: mutable.Map[String, Bucket] = mutable.HashMap()

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run(): Unit = {
      Thread.currentThread().setName("couchbase-shutdown-in-progress")
      CouchbaseConnectionPool().getConnection(cfg).stop()
      Thread.currentThread().setName("couchbase-shutdown-complete")
    }
  })

  def cluster(): Cluster = {
    this.synchronized {
      if (envRef.isEmpty) {
        // The spark connector does not use any transaction functionality, so make use of the backdoor
        // to disable txn processing and save resources.
        System.setProperty(CoreTransactionsCleanupConfig.TRANSACTIONS_CLEANUP_LOST_PROPERTY, "false")
        System.setProperty(CoreTransactionsCleanupConfig.TRANSACTIONS_CLEANUP_REGULAR_PROPERTY, "false")

        var builder = ClusterEnvironment.builder

        val parsedConnstr = ConnectionString.create(cfg.connectionString)
        if (cfg.sparkSslOptions.enabled || parsedConnstr.scheme() == ConnectionString.Scheme.COUCHBASES) {
          var securityConfig = SecurityConfig().enableTls(true)
          if (cfg.sparkSslOptions.keystorePath.isDefined) {
            securityConfig = securityConfig.trustStore(Paths.get(cfg.sparkSslOptions.keystorePath.get), cfg.sparkSslOptions.keystorePassword.get)
          } else if (cfg.sparkSslOptions.insecure) {
            securityConfig = securityConfig.trustManagerFactory(InsecureTrustManagerFactory.INSTANCE)
          }
          builder = builder.securityConfig(securityConfig)
        }
        builder = builder.loaders(Seq(new SparkPropertyLoader(cfg.properties)))

        envRef = Option(builder.build.get)
      }

      if (clusterRef.isEmpty) {
        clusterRef = Option(Cluster.connect(
          cfg.connectionString,
          ClusterOptions
            .create(cfg.credentials.username, cfg.credentials.password)
            .environment(envRef.get)
        ).get)

        val waitUntilReadyTimeout = cfg.waitUntilReadyTimeout.map(s => Duration(s)).getOrElse(1.minutes)
        clusterRef.get.waitUntilReady(waitUntilReadyTimeout)
      }

      if (cfg.bucketName.isDefined) {
        // If an implicit bucket is defined, we proactively open the bucket also to help with pre 6.5 cluster
        // compatibility.
        val _ = bucket(cfg.bucketName, clusterRef)
      }

      clusterRef.get
    }
  }

  def bucket(bucketName: Option[String]): Bucket = {
    bucket(bucketName, None)
  }

  private def bucket(bucketName: Option[String], c: Option[Cluster]): Bucket = {
    val bname = this.bucketName(bucketName)
    this.synchronized {
      if (bucketsRef.contains(bname)) {
        return bucketsRef(bname)
      }
      val bucket = c match {
        case Some(cl) => cl.bucket(bname)
        case None => cluster().bucket(bname)
      }
      bucketsRef.put(bname, bucket)

      val waitUntilReadyTimeout = cfg.waitUntilReadyTimeout.map(s => Duration(s)).getOrElse(1.minutes)
      bucket.waitUntilReady(waitUntilReadyTimeout)
      bucket
    }
  }

  def scope(bucketName: Option[String], scopeName: Option[String]): Scope = {
    val name = this.scopeName(scopeName)

    if (name.equals(CollectionIdentifier.DEFAULT_SCOPE)) {
      bucket(bucketName).defaultScope
    } else {
      bucket(bucketName).scope(name)
    }
  }

  def collection(bucketName: Option[String], scopeName: Option[String],
                 collectionName: Option[String]): Collection = {
    val parsedScopeName = this.scopeName(scopeName)
    val parsedCollectionName = this.collectionName(collectionName)

    if (parsedScopeName.equals(CollectionIdentifier.DEFAULT_SCOPE)
      && parsedCollectionName.equals(CollectionIdentifier.DEFAULT_COLLECTION)) {
      bucket(bucketName).defaultCollection
    } else {
      scope( bucketName, scopeName).collection(parsedCollectionName)
    }

  }

  def stop(): Unit = {
    this.synchronized {
      try {
        if (clusterRef.isDefined) {
          clusterRef.get.disconnect()
          clusterRef = None
        }
        if (envRef.isDefined) {
          envRef.get.shutdown()
          envRef = None
        }
        CouchbaseConnectionPool().closeConnection(cfg)
      } catch {
        case e: Throwable => logDebug(s"Encountered error during shutdown $e")
      }
    }
  }

  def bucketName(name: Option[String]): String = {
    name.orElse(cfg.bucketName) match {
      case Some(name) => name
      case None => throw InvalidArgumentException
        .fromMessage("No bucketName provided (neither configured globally, "
          + "nor in the per-command options)")
    }
  }

  private def scopeName(name: Option[String]): String = {
    name.orElse(cfg.scopeName) match {
      case Some(name) => name
      case None => CollectionIdentifier.DEFAULT_SCOPE
    }
  }

  private def collectionName(name: Option[String]): String = {
    name.orElse(cfg.collectionName) match {
      case Some(name) => name
      case None => CollectionIdentifier.DEFAULT_COLLECTION
    }
  }

  def dcpSeedNodes(): String = {
    val allSeedNodes = cluster().async.core.configurationProvider()
      .seedNodes()
      .blockFirst().asScala
      .map(_.address())

    allSeedNodes.mkString(",")
  }

  def dcpSecurityConfig(): com.couchbase.client.dcp.SecurityConfig = {
    // Make sure we are bootstrapped.
    val _ = cluster()

    val coreSecurityConfig = envRef.get.core.securityConfig()

    val dcpSecurityConfig = com.couchbase.client.dcp.SecurityConfig.builder()

    dcpSecurityConfig.enableTls(coreSecurityConfig.tlsEnabled())
    dcpSecurityConfig.enableNativeTls(coreSecurityConfig.nativeTlsEnabled())
    dcpSecurityConfig.enableHostnameVerification(coreSecurityConfig.hostnameVerificationEnabled())

    if (coreSecurityConfig.trustManagerFactory() != null) {
      dcpSecurityConfig.trustManagerFactory(coreSecurityConfig.trustManagerFactory())
    }
    if (coreSecurityConfig.trustCertificates() != null) {
      dcpSecurityConfig.trustCertificates(coreSecurityConfig.trustCertificates())
    }

    dcpSecurityConfig.build()
  }

}

object CouchbaseConnection {

  def apply(cfg: CouchbaseConfig) = new CouchbaseConnection(cfg)

}

class SparkPropertyLoader(properties: Seq[(String, String)])
  extends AbstractMapPropertyLoader[CoreEnvironment.Builder[_]]{
  override def propertyMap(): util.Map[String, String] = {
    properties.toMap.asJava
  }
}
