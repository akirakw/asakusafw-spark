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

import org.apache.hadoop.io.Writable
import org.objectweb.asm.{ Opcodes, Type }

import com.asakusafw.lang.compiler.model.graph.UserOperator
import com.asakusafw.lang.compiler.planning.SubPlan
import com.asakusafw.spark.compiler.planning.SubPlanOutputInfo
import com.asakusafw.spark.runtime.io.WritableSerDe
import com.asakusafw.spark.runtime.rdd.BranchKey
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._
import com.asakusafw.spark.tools.asm4s._

trait Serializing extends ClassBuilder {

  implicit def context: Serializing.Context

  def subplanOutputs: Seq[SubPlan.Output]

  override def defFields(fieldDef: FieldDef): Unit = {
    super.defFields(fieldDef)

    for {
      (output, i) <- subplanOutputs.zipWithIndex
      outputInfo = output.getAttribute(classOf[SubPlanOutputInfo])
      if outputInfo.getOutputType != SubPlanOutputInfo.OutputType.BROADCAST
    } {
      fieldDef.newField(
        Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT,
        s"value${i}",
        outputType(output))
    }
  }

  override def defMethods(methodDef: MethodDef): Unit = {
    super.defMethods(methodDef)

    methodDef.newMethod("serialize", classOf[Array[Byte]].asType,
      Seq(classOf[BranchKey].asType, classOf[AnyRef].asType)) { implicit mb =>
        val thisVar :: branchVar :: valueVar :: _ = mb.argVars

        `return`(
          thisVar.push()
            .invokeV("serialize", classOf[Array[Byte]].asType,
              branchVar.push(),
              valueVar.push().cast(classOf[Writable].asType)))
      }

    methodDef.newMethod("serialize", classOf[Array[Byte]].asType,
      Seq(classOf[BranchKey].asType, classOf[Writable].asType)) { implicit mb =>
        val thisVar :: branchVar :: valueVar :: _ = mb.argVars

        `return`(
          pushObject(WritableSerDe)
            .invokeV("serialize", classOf[Array[Byte]].asType, valueVar.push()))
      }

    methodDef.newMethod("deserialize", classOf[AnyRef].asType,
      Seq(classOf[BranchKey].asType, classOf[Array[Byte]].asType)) { implicit mb =>
        val thisVar :: branchVar :: valueVar :: _ = mb.argVars

        `return`(
          thisVar.push()
            .invokeV("deserialize", classOf[Writable].asType,
              branchVar.push(),
              valueVar.push()))
      }

    methodDef.newMethod("deserialize", classOf[Writable].asType,
      Seq(classOf[BranchKey].asType, classOf[Array[Byte]].asType)) { implicit mb =>
        val thisVar :: branchVar :: sliceVar :: _ = mb.argVars

        val valueVar =
          thisVar.push().invokeV("value", classOf[Writable].asType, branchVar.push())
            .store()
        pushObject(WritableSerDe)
          .invokeV(
            "deserialize",
            sliceVar.push(),
            valueVar.push())
        `return`(valueVar.push())
      }

    methodDef.newMethod(
      "value", classOf[Writable].asType, Seq(classOf[BranchKey].asType)) { implicit mb =>
        val thisVar :: branchVar :: _ = mb.argVars

        for {
          (output, i) <- subplanOutputs.zipWithIndex
        } {
          branchVar.push().unlessNotEqual(context.branchKeys.getField(output.getOperator)) {
            val t = outputType(output)
            val outputInfo = output.getAttribute(classOf[SubPlanOutputInfo])
            if (outputInfo.getOutputType == SubPlanOutputInfo.OutputType.BROADCAST) {
              `return`(pushNew0(t))
            } else {
              thisVar.push().getField(s"value${i}", t).unlessNotNull {
                thisVar.push().putField(s"value${i}", pushNew0(t))
              }
              `return`(thisVar.push().getField(s"value${i}", t))
            }
          }
        }
        `throw`(pushNew0(classOf[AssertionError].asType))
      }
  }

  private def outputType(output: SubPlan.Output): Type = {
    val outputInfo = output.getAttribute(classOf[SubPlanOutputInfo])
    if (outputInfo.getOutputType == SubPlanOutputInfo.OutputType.AGGREGATED) {
      val operator = outputInfo.getAggregationInfo.asInstanceOf[UserOperator]
      assert(operator.outputs.size == 1,
        s"The size of outputs should be 1: ${operator.outputs.size} [${operator}]")
      operator.outputs.head.getDataType.asType
    } else {
      output.getOperator.getDataType.asType
    }
  }
}

object Serializing {

  trait Context {

    def branchKeys: BranchKeys
  }
}
