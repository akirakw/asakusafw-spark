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
package operator
package core

import org.apache.spark.broadcast.Broadcast
import org.objectweb.asm.{ Opcodes, Type }
import org.objectweb.asm.signature.SignatureVisitor

import com.asakusafw.spark.runtime.driver.BroadcastId
import com.asakusafw.spark.runtime.fragment.Fragment
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._

abstract class CoreOperatorFragmentClassBuilder(
  dataModelType: Type,
  val childDataModelType: Type)(
    implicit context: SparkClientCompiler.Context)
  extends FragmentClassBuilder(dataModelType) {

  override def defFields(fieldDef: FieldDef): Unit = {
    super.defFields(fieldDef)

    fieldDef.newField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "child", classOf[Fragment[_]].asType,
      new TypeSignatureBuilder()
        .newClassType(classOf[Fragment[_]].asType) {
          _.newTypeArgument(SignatureVisitor.INSTANCEOF, childDataModelType)
        }
        .build())
    fieldDef.newField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "childDataModel", childDataModelType)
  }

  override def defConstructors(ctorDef: ConstructorDef): Unit = {
    ctorDef.newInit(
      Seq(classOf[Map[BroadcastId, Broadcast[_]]].asType, classOf[Fragment[_]].asType),
      new MethodSignatureBuilder()
        .newParameterType {
          _.newClassType(classOf[Map[_, _]].asType) {
            _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[BroadcastId].asType)
              .newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[Broadcast[_]].asType)
          }
        }
        .newParameterType {
          _.newClassType(classOf[Fragment[_]].asType) {
            _.newTypeArgument(SignatureVisitor.INSTANCEOF, childDataModelType)
          }
        }
        .newVoidReturnType()
        .build()) { mb =>
        import mb._ // scalastyle:ignore
        val broadcastsVar = `var`(classOf[Map[BroadcastId, Broadcast[_]]].asType, thisVar.nextLocal)
        val childVar = `var`(classOf[Fragment[_]].asType, broadcastsVar.nextLocal)

        thisVar.push().invokeInit(superType)
        thisVar.push().putField("child", classOf[Fragment[_]].asType, childVar.push())
        thisVar.push().putField("childDataModel", childDataModelType, pushNew0(childDataModelType))
        initReset(mb)
      }
  }

  override def defReset(mb: MethodBuilder): Unit = {
    import mb._ // scalastyle:ignore
    unlessReset(mb) {
      thisVar.push().getField("child", classOf[Fragment[_]].asType).invokeV("reset")
    }
    `return`()
  }
}