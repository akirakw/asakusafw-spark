/*
 * Copyright 2011-2019 Asakusa Framework Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.asakusafw.spark.runtime
package graph

import scala.annotation.meta.param
import scala.concurrent.{ ExecutionContext, Future }

import org.apache.spark.Partitioner
import org.apache.spark.rdd.RDD

import com.asakusafw.spark.runtime.Props
import com.asakusafw.spark.runtime.rdd._

abstract class Extract[T](
  @(transient @param) prevs: Seq[(Source, BranchKey)])(
    @transient val broadcasts: Map[BroadcastId, Broadcast[_]])(
      implicit val jobContext: JobContext)
  extends Source
  with UsingBroadcasts
  with Branching[T] {
  self: CacheStrategy[RoundContext, Map[BranchKey, Future[() => RDD[_]]]] =>

  @transient
  private val fragmentBufferSize =
    jobContext.sparkContext.getConf.getInt(
      Props.FragmentBufferSize, Props.DefaultFragmentBufferSize)

  override def doCompute(
    rc: RoundContext)(implicit ec: ExecutionContext): Map[BranchKey, Future[() => RDD[_]]] = {

    val rdds = prevs.map {
      case (source, branchKey) =>
        source.compute(rc).apply(branchKey).map(_().asInstanceOf[RDD[(_, T)]])
    }

    val future =
      (if (rdds.size == 1) {
        rdds.head
      } else {
        Future.sequence(rdds).map { prevs =>
          withCallSite(rc) {
            val zipped =
              prevs.groupBy(_.partitions.length).map {
                case (_, rdds) =>
                  rdds.reduce(_.zipPartitions(_, preservesPartitioning = false)(_ ++ _))
              }.toSeq
            if (zipped.size == 1) {
              zipped.head
            } else {
              jobContext.sparkContext.union(zipped)
            }
          }
        }
      }).zip(zipBroadcasts(rc)).map {
        case (prev, broadcasts) =>
          withCallSite(rc) {
            branch(prev, broadcasts, rc.hadoopConf)(fragmentBufferSize)
          }
      }

    branchKeys.map(key => key -> future.map(_(key))).toMap
  }
}
