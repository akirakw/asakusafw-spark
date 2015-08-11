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
package user
package join

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.UserOperator
import com.asakusafw.spark.compiler.spi.OperatorType
import com.asakusafw.vocabulary.operator.{ MasterBranch => MasterBranchOp }

class BroadcastMasterBranchOperatorCompiler extends UserOperatorCompiler {

  override def support(
    operator: UserOperator)(
      implicit context: SparkClientCompiler.Context): Boolean = {
    operator.annotationDesc.resolveClass == classOf[MasterBranchOp]
  }

  override def operatorType: OperatorType = OperatorType.ExtractType

  override def compile(
    operator: UserOperator)(
      implicit context: SparkClientCompiler.Context): Type = {

    assert(support(operator),
      s"The operator type is not supported: ${operator.annotationDesc.resolveClass.getSimpleName}")
    assert(operator.inputs.size == 2, // FIXME to take multiple inputs for side data?
      s"The size of inputs should be 2: ${operator.inputs.size}")
    assert(operator.outputs.size > 0,
      s"The size of outputs should be greater than 0: ${operator.outputs.size}")

    assert(
      operator.outputs.forall(output =>
        output.dataModelType == operator.inputs(MasterBranchOp.ID_INPUT_TRANSACTION).dataModelType),
      s"All of output types should be the same as the transaction type: ${
        operator.outputs.map(_.dataModelType).mkString("(", ",", ")")
      }")

    assert(
      operator.methodDesc.parameterClasses
        .zip(operator.inputs.map(_.dataModelClass)
          ++: operator.arguments.map(_.resolveClass))
        .forall {
          case (method, model) => method.isAssignableFrom(model)
        },
      s"The operator method parameter types are not compatible: (${
        operator.methodDesc.parameterClasses.map(_.getName).mkString("(", ",", ")")
      }, ${
        (operator.inputs.map(_.dataModelClass)
          ++: operator.arguments.map(_.resolveClass)).map(_.getName).mkString("(", ",", ")")
      })")

    val builder =
      new BroadcastJoinOperatorFragmentClassBuilder(
        operator,
        operator.inputs(MasterBranchOp.ID_INPUT_MASTER),
        operator.inputs(MasterBranchOp.ID_INPUT_TRANSACTION)) with MasterBranch

    context.jpContext.addClass(builder)
  }
}