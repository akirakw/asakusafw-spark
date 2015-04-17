package com.asakusafw.spark.compiler
package operator
package core

import scala.collection.JavaConversions._

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.analyzer.util.ProjectionOperatorUtil
import com.asakusafw.lang.compiler.model.graph.CoreOperator
import com.asakusafw.lang.compiler.model.graph.CoreOperator.CoreOperatorKind
import com.asakusafw.spark.compiler.spi.OperatorType
import com.asakusafw.spark.runtime.fragment.Fragment
import com.asakusafw.spark.runtime.util.ValueOptionOps
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._

class ProjectionOperatorsCompiler extends CoreOperatorCompiler {

  override def support(operator: CoreOperator)(implicit context: Context): Boolean = {
    operator.getCoreOperatorKind == CoreOperatorKind.PROJECT ||
      operator.getCoreOperatorKind == CoreOperatorKind.EXTEND ||
      operator.getCoreOperatorKind == CoreOperatorKind.RESTRUCTURE
  }

  override def operatorType: OperatorType = OperatorType.MapType

  override def compile(operator: CoreOperator)(implicit context: Context): Type = {

    val operatorInfo = new OperatorInfo(operator)(context.jpContext)
    import operatorInfo._

    assert(support(operator),
      s"The operator type is not supported: ${operator.getCoreOperatorKind}")
    assert(inputs.size == 1,
      s"The size of inputs should be 1: ${inputs.size}")
    assert(outputs.size == 1,
      s"The size of outputs should be 1: ${outputs.size}")

    val mappings = ProjectionOperatorUtil.getPropertyMappings(context.jpContext.getDataModelLoader, operator).toSeq

    val builder = new CoreOperatorFragmentClassBuilder(
      context.flowId,
      inputs.head.dataModelType,
      outputs.head.dataModelType) {

      override def defAddMethod(mb: MethodBuilder, dataModelVar: Var): Unit = {
        import mb._

        thisVar.push().getField("childDataModel", childDataModelType).invokeV("reset")

        mappings.foreach { mapping =>
          val srcProperty = mapping.getSourcePort.dataModelRef
            .findProperty(mapping.getSourceProperty)
          val destProperty = mapping.getDestinationPort.dataModelRef
            .findProperty(mapping.getDestinationProperty)
          assert(srcProperty.getType.asType == destProperty.getType.asType,
            s"The source and destination types should be the same: (${srcProperty.getType}, ${destProperty.getType}")

          getStatic(ValueOptionOps.getClass.asType, "MODULE$", ValueOptionOps.getClass.asType)
            .invokeV(
              "copy",
              dataModelVar.push()
                .invokeV(srcProperty.getDeclaration.getName, srcProperty.getType.asType),
              thisVar.push().getField("childDataModel", childDataModelType)
                .invokeV(destProperty.getDeclaration.getName, destProperty.getType.asType))
        }

        thisVar.push().getField("child", classOf[Fragment[_]].asType)
          .invokeV("add", thisVar.push()
            .getField("childDataModel", childDataModelType)
            .asType(classOf[AnyRef].asType))
        `return`()
      }
    }

    context.jpContext.addClass(builder)
  }
}