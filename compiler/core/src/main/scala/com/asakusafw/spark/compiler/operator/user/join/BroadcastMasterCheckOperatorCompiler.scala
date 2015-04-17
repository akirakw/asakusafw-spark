package com.asakusafw.spark.compiler
package operator
package user
package join

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.api.JobflowProcessor.{ Context => JPContext }
import com.asakusafw.lang.compiler.model.graph.{ MarkerOperator, OperatorInput, UserOperator }
import com.asakusafw.spark.compiler.spi.OperatorType
import com.asakusafw.spark.compiler.subplan.BroadcastIdsClassBuilder
import com.asakusafw.vocabulary.operator.{ MasterCheck => MasterCheckOp }

class BroadcastMasterCheckOperatorCompiler extends UserOperatorCompiler {

  override def support(operator: UserOperator)(implicit context: Context): Boolean = {
    val operatorInfo = new OperatorInfo(operator)(context.jpContext)
    import operatorInfo._
    annotationDesc.resolveClass == classOf[MasterCheckOp]
  }

  override def operatorType: OperatorType = OperatorType.MapType

  override def compile(operator: UserOperator)(implicit context: Context): Type = {

    val operatorInfo = new OperatorInfo(operator)(context.jpContext)
    import operatorInfo._

    assert(support(operator),
      s"The operator type is not supported: ${annotationDesc.resolveClass.getSimpleName}")
    assert(inputs.size == 2, // FIXME to take multiple inputs for side data?
      s"The size of inputs should be 2: ${inputs.size}")

    assert(
      outputs.forall { output =>
        output.dataModelType == inputs(MasterCheckOp.ID_INPUT_TRANSACTION).dataModelType
      },
      s"All of output types should be the same as the transaction type: ${
        outputs.map(_.dataModelType).mkString("(", ",", ")")
      }")

    val builder = new JoinOperatorFragmentClassBuilder(
      context.flowId,
      inputs(MasterCheckOp.ID_INPUT_TRANSACTION).dataModelType,
      implementationClassType,
      outputs) with BroadcastJoin with MasterCheck {

      val jpContext: JPContext = context.jpContext

      val broadcastIds: BroadcastIdsClassBuilder = context.broadcastIds

      val shuffleKeyTypes: mutable.Set[Type] = context.shuffleKeyTypes

      lazy val masterInput: OperatorInput = inputs(MasterCheckOp.ID_INPUT_MASTER)
      lazy val txInput: OperatorInput = inputs(MasterCheckOp.ID_INPUT_TRANSACTION)

      lazy val masterType: Type = masterInput.dataModelType
      lazy val txType: Type = dataModelType
      lazy val masterSelection: Option[(String, Type)] = selectionMethod

      val opInfo: OperatorInfo = operatorInfo
    }

    context.jpContext.addClass(builder)
  }
}