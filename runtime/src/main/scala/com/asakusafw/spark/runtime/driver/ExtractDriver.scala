package com.asakusafw.spark.runtime.driver

import scala.concurrent.Future

import org.apache.hadoop.conf.Configuration
import org.apache.spark.{ Partitioner, SparkContext }
import org.apache.spark.rdd.{ RDD, UnionRDD }
import org.apache.spark.broadcast.Broadcast

import com.asakusafw.spark.runtime.SparkClient.executionContext
import com.asakusafw.spark.runtime.rdd._

abstract class ExtractDriver[T](
  sc: SparkContext,
  hadoopConf: Broadcast[Configuration],
  broadcasts: Map[BroadcastId, Future[Broadcast[_]]],
  @transient prevs: Seq[Future[RDD[(_, T)]]])
    extends SubPlanDriver(sc, hadoopConf, broadcasts) with Branching[T] {
  assert(prevs.size > 0,
    s"Previous RDDs should be more than 0: ${prevs.size}")

  override def execute(): Map[BranchKey, Future[RDD[(ShuffleKey, _)]]] = {

    val future = (if (prevs.size == 1) {
      prevs.head
    } else {
      Future.sequence(prevs).map { prevs =>
        val part = Partitioner.defaultPartitioner(prevs.head, prevs.tail: _*)
        val (unioning, coalescing) = prevs.partition(_.partitions.size < part.numPartitions)
        val coalesced = zipPartitions(
          coalescing.map {
            case prev if prev.partitions.size == part.numPartitions => prev
            case prev => prev.coalesce(part.numPartitions, shuffle = false)
          }, preservesPartitioning = false) {
            _.iterator.flatten.asInstanceOf[Iterator[(_, T)]]
          }
        if (unioning.isEmpty) {
          coalesced
        } else {
          new UnionRDD(sc, coalesced +: unioning)
        }
      }
    }).map { prev =>
      sc.clearCallSite()
      sc.setCallSite(label)

      branch(prev)
    }

    branchKeys.map(key => key -> future.map(_(key))).toMap
  }
}