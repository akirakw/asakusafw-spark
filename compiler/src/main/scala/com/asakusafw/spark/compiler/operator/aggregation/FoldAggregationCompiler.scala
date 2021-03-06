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
package com.asakusafw.spark.compiler
package operator
package aggregation

import scala.collection.JavaConversions._
import scala.reflect.ClassTag

import org.apache.spark.broadcast.{ Broadcast => Broadcasted }
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureVisitor

import com.asakusafw.lang.compiler.model.graph.{ OperatorInput, UserOperator }
import com.asakusafw.runtime.core.GroupView
import com.asakusafw.spark.compiler.spi.AggregationCompiler
import com.asakusafw.spark.runtime.graph.BroadcastId
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._
import com.asakusafw.vocabulary.operator.Fold

class FoldAggregationCompiler extends AggregationCompiler {

  def of: Class[_] = classOf[Fold]

  def compile(
    operator: UserOperator)(
      implicit context: AggregationCompiler.Context): Type = {

    assert(operator.annotationDesc.resolveClass == of,
      s"The operator type is not supported: ${operator.annotationDesc.resolveClass.getSimpleName}"
        + s" [${operator}]")
    assert(operator.inputs.size >= 1,
      "The size of inputs should be greater than or equals to 1: " +
        s"${operator.inputs.size} [${operator}]")
    assert(operator.outputs.size == 1,
      s"The size of outputs should be 1: ${operator.outputs.size} [${operator}]")
    assert(
      operator.inputs(Fold.ID_INPUT).dataModelType
        == operator.outputs(Fold.ID_OUTPUT).dataModelType,
      s"The data models are not the same type: (${
        operator.inputs(Fold.ID_INPUT).dataModelType
      }, ${
        operator.outputs(Fold.ID_OUTPUT).dataModelType
      }) [${operator}]")

    assert(
      operator.methodDesc.parameterClasses
        .zip(operator.inputs.take(1).map(_.dataModelClass)
          ++: operator.outputs.map(_.dataModelClass)
          ++: operator.inputs.drop(1).collect {
            case input: OperatorInput if input.getInputUnit == OperatorInput.InputUnit.WHOLE =>
              classOf[GroupView[_]]
          }
          ++: operator.arguments.map(_.resolveClass))
        .forall {
          case (method, model) => method.isAssignableFrom(model)
        },
      s"The operator method parameter types are not compatible: (${
        operator.methodDesc.parameterClasses.map(_.getName).mkString("(", ",", ")")
      }, ${
        (operator.inputs.take(1).map(_.dataModelClass)
          ++: operator.outputs.map(_.dataModelClass)
          ++: operator.inputs.drop(1).collect {
            case input: OperatorInput if input.getInputUnit == OperatorInput.InputUnit.WHOLE =>
              classOf[GroupView[_]]
          }
          ++: operator.arguments.map(_.resolveClass)).map(_.getName).mkString("(", ",", ")")
      }) [${operator}]")

    val builder = new FoldAggregationClassBuilder(operator)

    context.addClass(builder)
  }
}

private class FoldAggregationClassBuilder(
  operator: UserOperator)(
    implicit context: AggregationCompiler.Context)
  extends AggregationClassBuilder(
    operator.inputs(Fold.ID_INPUT).dataModelType,
    operator.outputs(Fold.ID_OUTPUT).dataModelType)(
    AggregationClassBuilder.AggregationType.Fold)
  with OperatorField with ViewFields {

  val operatorType = operator.implementationClass.asType

  val operatorInputs: Seq[OperatorInput] = operator.inputs

  override def defConstructors(ctorDef: ConstructorDef): Unit = {
    ctorDef.newInit(
      Seq(classOf[Map[BroadcastId, Broadcasted[_]]].asType),
      new MethodSignatureBuilder()
        .newParameterType {
          _.newClassType(classOf[Map[_, _]].asType) {
            _.newTypeArgument(SignatureVisitor.INSTANCEOF, classOf[BroadcastId].asType)
              .newTypeArgument(SignatureVisitor.INSTANCEOF) {
                _.newClassType(classOf[Broadcasted[_]].asType) {
                  _.newTypeArgument()
                }
              }
          }
        }
        .newVoidReturnType()) { implicit mb =>
        val thisVar :: broadcastsVar :: _ = mb.argVars
        thisVar.push().invokeInit(superType)
        initOperatorField()
        initViewFields(broadcastsVar)
      }
  }

  override def defNewCombiner()(implicit mb: MethodBuilder): Unit = {
    `return`(pushNew0(combinerType))
  }

  override def defInitCombinerByValue(
    combinerVar: Var, valueVar: Var)(implicit mb: MethodBuilder): Unit = {
    combinerVar.push().invokeV("copyFrom", valueVar.push())
    `return`(combinerVar.push())
  }

  override def defMergeValue(
    combinerVar: Var, valueVar: Var)(implicit mb: MethodBuilder): Unit = {
    getOperatorField().invokeV(
      operator.methodDesc.getName,
      (combinerVar.push()
        +: valueVar.push()
        +: operator.inputs.drop(1).collect {
          case input: OperatorInput if input.getInputUnit == OperatorInput.InputUnit.WHOLE =>
            getViewField(input)
        }
        ++: operator.arguments.map { argument =>
          Option(argument.value).map { value =>
            ldc(value)(ClassTag(argument.resolveClass), implicitly)
          }.getOrElse {
            pushNull(argument.resolveClass.asType)
          }
        }).zip(operator.methodDesc.asType.getArgumentTypes()).map {
          case (s, t) => s.asType(t)
        }: _*)
    `return`(combinerVar.push())
  }

  override def defInitCombinerByCombiner(
    comb1Var: Var, comb2Var: Var)(implicit mb: MethodBuilder): Unit = {
    comb1Var.push().invokeV("copyFrom", comb2Var.push())
    `return`(comb1Var.push())
  }

  override def defMergeCombiners(
    comb1Var: Var, comb2Var: Var)(implicit mb: MethodBuilder): Unit = {
    val thisVar :: _ = mb.argVars
    `return`(
      thisVar.push().invokeV("mergeValue", combinerType, comb1Var.push(), comb2Var.push()))
  }
}
