package com.asakusafw.spark.runtime

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.hadoop.conf.Configuration
import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD

import org.apache.spark.backdoor._
import org.apache.spark.util.backdoor._
import com.asakusafw.spark.runtime.driver.ShuffleKey
import com.asakusafw.spark.runtime.rdd._

abstract class SparkClient {

  def execute(conf: SparkConf): Int = {
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.kryo.registrator", kryoRegistrator)
    conf.set("spark.kryo.referenceTracking", false.toString)

    val sc = new SparkContext(conf)
    try {
      val hadoopConf = sc.broadcast(sc.hadoopConfiguration)
      execute(sc, hadoopConf)
    } finally {
      sc.stop()
    }
  }

  def execute(sc: SparkContext, hadoopConf: Broadcast[Configuration]): Int

  def kryoRegistrator: String

  def broadcastAsHash[V](
    sc: SparkContext,
    label: String,
    prev: Future[RDD[(ShuffleKey, V)]],
    sort: Option[ShuffleKey.SortOrdering],
    grouping: ShuffleKey.GroupingOrdering,
    part: Partitioner): Future[Broadcast[Map[ShuffleKey, Seq[V]]]] = {

    prev.map { p =>
      sc.clearCallSite()
      sc.setCallSite(label)

      val rdd = smcogroup(
        Seq((p.asInstanceOf[RDD[(ShuffleKey, _)]], sort)),
        part,
        grouping)
        .map { case (k, vs) => (k.dropOrdering, vs(0).toVector.asInstanceOf[Seq[V]]) }

      val results =
        sc.runJob(
          rdd,
          (iter: Iterator[(ShuffleKey, Seq[V])]) => iter.toVector,
          0 until rdd.partitions.size,
          allowLocal = true)
      sc.broadcast(results.flatten.toMap)
    }
  }
}
