package com.asakusafw.spark.compiler
package operator
package user

import scala.concurrent.Future

import org.apache.spark.broadcast.Broadcast
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureVisitor

import com.asakusafw.lang.compiler.model.graph.OperatorOutput
import com.asakusafw.spark.runtime.driver.BroadcastId
import com.asakusafw.spark.runtime.fragment.Fragment
import com.asakusafw.spark.tools.asm._

abstract class UserOperatorFragmentClassBuilder(
  flowId: String,
  dataModelType: Type,
  val operatorType: Type,
  val operatorOutputs: Seq[OperatorOutput])
    extends FragmentClassBuilder(flowId, dataModelType)
    with OperatorField
    with OutputFragments {

  override def defFields(fieldDef: FieldDef): Unit = {
    defOperatorField(fieldDef)
    defOutputFields(fieldDef)
  }

  override def defConstructors(ctorDef: ConstructorDef): Unit = {
    ctorDef.newInit(
      classOf[Map[BroadcastId, Broadcast[_]]].asType +: (0 until operatorOutputs.size).map(_ => classOf[Fragment[_]].asType),
      ((new MethodSignatureBuilder()
        .newParameterType {
          _.newClassType(classOf[Map[_, _]].asType) {
            _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[BroadcastId].asType)
              .newTypeArgument(SignatureVisitor.INSTANCEOF) {
                _.newClassType(classOf[Broadcast[_]].asType) {
                  _.newTypeArgument()
                }
              }
          }
        } /: operatorOutputs) {
          case (builder, output) =>
            builder.newParameterType {
              _.newClassType(classOf[Fragment[_]].asType) {
                _.newTypeArgument(SignatureVisitor.INSTANCEOF, output.getDataType.asType)
              }
            }
        })
        .newVoidReturnType()
        .build()) { mb =>
        import mb._
        val broadcastsVar = `var`(classOf[Map[BroadcastId, Broadcast[_]]].asType, thisVar.nextLocal)

        thisVar.push().invokeInit(superType)
        initOutputFields(mb, broadcastsVar.nextLocal)
        initOperatorField(mb)
        initFields(mb)
      }
  }

  def initFields(mb: MethodBuilder): Unit = {}

  override def defMethods(methodDef: MethodDef): Unit = {
    super.defMethods(methodDef)

    methodDef.newMethod("reset", Seq.empty) { mb =>
      import mb._
      resetOutputs(mb)
      `return`()
    }
  }
}
