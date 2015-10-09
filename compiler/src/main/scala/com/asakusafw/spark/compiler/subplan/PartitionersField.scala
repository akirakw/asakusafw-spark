/*
 * Copyright 2011-2015 Asakusa Framework Team.
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
package com.asakusafw.spark.compiler
package subplan

import scala.collection.JavaConversions._

import org.apache.spark.{ Partitioner, SparkConf, SparkContext }
import org.objectweb.asm.{ Opcodes, Type }
import org.objectweb.asm.signature.SignatureVisitor

import com.asakusafw.lang.compiler.planning.{ PlanMarker, SubPlan }
import com.asakusafw.spark.compiler.planning.SubPlanOutputInfo
import com.asakusafw.spark.compiler.spi.SubPlanCompiler
import com.asakusafw.spark.runtime.rdd.{ BranchKey, IdentityPartitioner }
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._

trait PartitionersField
  extends ClassBuilder
  with NumPartitions
  with ScalaIdioms
  with SparkIdioms {

  implicit def context: PartitionersField.Context

  def subplanOutputs: Seq[SubPlan.Output]

  override def defFields(fieldDef: FieldDef): Unit = {
    super.defFields(fieldDef)

    fieldDef.newField(
      Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT,
      "partitioners",
      classOf[Map[_, _]].asType,
      new TypeSignatureBuilder()
        .newClassType(classOf[Map[_, _]].asType) {
          _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[BranchKey].asType)
            .newTypeArgument(SignatureVisitor.INSTANCEOF) {
              _.newClassType(classOf[Option[_]].asType) {
                _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[Partitioner].asType)
              }
            }
        }
        .build())
  }

  override def defMethods(methodDef: MethodDef): Unit = {
    super.defMethods(methodDef)

    methodDef.newMethod("partitioners", classOf[Map[_, _]].asType, Seq.empty,
      new MethodSignatureBuilder()
        .newReturnType {
          _.newClassType(classOf[Map[_, _]].asType) {
            _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[BranchKey].asType)
              .newTypeArgument(SignatureVisitor.INSTANCEOF) {
                _.newClassType(classOf[Option[_]].asType) {
                  _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[Partitioner].asType)
                }
              }
          }
        }
        build ()) { mb =>
        import mb._ // scalastyle:ignore
        thisVar.push().getField("partitioners", classOf[Map[_, _]].asType).unlessNotNull {
          thisVar.push().putField("partitioners", classOf[Map[_, _]].asType, initPartitioners(mb))
        }
        `return`(thisVar.push().getField("partitioners", classOf[Map[_, _]].asType))
      }
  }

  def getPartitionersField(mb: MethodBuilder): Stack = {
    import mb._ // scalastyle:ignore
    thisVar.push().invokeV("partitioners", classOf[Map[_, _]].asType)
  }

  private def initPartitioners(mb: MethodBuilder): Stack = {
    import mb._ // scalastyle:ignore
    buildMap(mb) { builder =>
      val np = numPartitions(mb)(thisVar.push().invokeV("sc", classOf[SparkContext].asType)) _
      for {
        output <- subplanOutputs.sortBy(_.getOperator.getSerialNumber)
        outputInfo <- Option(output.getAttribute(classOf[SubPlanOutputInfo]))
        if outputInfo.getOutputType == SubPlanOutputInfo.OutputType.DONT_CARE ||
          outputInfo.getOutputType == SubPlanOutputInfo.OutputType.AGGREGATED ||
          outputInfo.getOutputType == SubPlanOutputInfo.OutputType.PARTITIONED ||
          outputInfo.getOutputType == SubPlanOutputInfo.OutputType.BROADCAST
      } {
        builder += (
          context.branchKeys.getField(mb, output.getOperator),
          outputInfo.getOutputType match {
            case SubPlanOutputInfo.OutputType.DONT_CARE =>
              pushObject(mb)(None)
            case SubPlanOutputInfo.OutputType.AGGREGATED |
              SubPlanOutputInfo.OutputType.PARTITIONED if outputInfo.getPartitionInfo.getGrouping.nonEmpty => // scalastyle:ignore
              option(mb)(partitioner(mb)(np(output)))
            case _ =>
              option(mb)(partitioner(mb)(ldc(1)))
          })
      }
    }
  }
}

object PartitionersField {

  trait Context {

    def branchKeys: BranchKeys
  }
}
