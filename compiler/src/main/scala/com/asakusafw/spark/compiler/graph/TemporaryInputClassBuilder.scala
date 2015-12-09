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
package graph

import java.util.concurrent.atomic.AtomicLong

import org.apache.spark.SparkContext
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureVisitor

import com.asakusafw.lang.compiler.model.graph.ExternalInput
import com.asakusafw.lang.compiler.planning.SubPlan
import com.asakusafw.spark.compiler.graph.TemporaryInputClassBuilder._
import com.asakusafw.spark.compiler.spi.NodeCompiler
import com.asakusafw.spark.runtime.graph.{ Broadcast, BroadcastId, TemporaryInput }
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._
import com.asakusafw.spark.tools.asm4s._

class TemporaryInputClassBuilder(
  operator: ExternalInput,
  valueType: Type,
  paths: Seq[String],
  computeStrategy: MixIn)(
    label: String,
    subplanOutputs: Seq[SubPlan.Output])(
      implicit context: NodeCompiler.Context)
  extends NewHadoopInputClassBuilder(
    operator,
    valueType,
    computeStrategy)(
    label,
    subplanOutputs)(
    Type.getType(
      s"L${GeneratedClassPackageInternalName}/${context.flowId}/graph/TemporaryInput$$${nextId};"),
    new ClassSignatureBuilder()
      .newSuperclass {
        _.newClassType(classOf[TemporaryInput[_]].asType) {
          _.newTypeArgument(SignatureVisitor.INSTANCEOF, valueType)
        }
      }
      .build(),
    classOf[TemporaryInput[_]].asType) {

  override def defConstructors(ctorDef: ConstructorDef): Unit = {
    ctorDef.newInit(Seq(
      classOf[Map[BroadcastId, Broadcast]].asType,
      classOf[SparkContext].asType),
      new MethodSignatureBuilder()
        .newParameterType {
          _.newClassType(classOf[Map[_, _]].asType) {
            _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[BroadcastId].asType)
              .newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[Broadcast].asType)
          }
        }
        .newParameterType(classOf[SparkContext].asType)
        .newVoidReturnType()
        .build()) { implicit mb =>

        val thisVar :: broadcastsVar :: scVar :: _ = mb.argVars

        thisVar.push().invokeInit(
          superType,
          broadcastsVar.push(),
          classTag(valueType),
          scVar.push())
        initMixIns()
      }
  }

  override def defMethods(methodDef: MethodDef): Unit = {
    super.defMethods(methodDef)

    methodDef.newMethod("paths", classOf[Set[String]].asType, Seq.empty,
      new MethodSignatureBuilder()
        .newReturnType {
          _.newClassType(classOf[Set[_]].asType) {
            _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[String].asType)
          }
        }
        .build()) { implicit mb =>

        `return`(
          buildSet { builder =>
            for {
              path <- paths
            } {
              builder += ldc(path)
            }
          })
      }
  }
}

object TemporaryInputClassBuilder {

  private[this] val curId: AtomicLong = new AtomicLong(0L)

  def nextId: Long = curId.getAndIncrement
}