package com.asakusafw.spark.compiler
package subplan

import java.util.concurrent.atomic.AtomicLong

import org.objectweb.asm._
import org.objectweb.asm.signature.SignatureVisitor
import org.apache.spark._

import com.asakusafw.lang.compiler.model.graph.MarkerOperator
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.spark.runtime.driver.CoGroupDriver
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._

abstract class CoGroupDriverClassBuilder(
  val flowId: String,
  val groupingKeyType: Type)
    extends ClassBuilder(
      Type.getType(s"L${GeneratedClassPackageInternalName}/${flowId}/driver/CoGroupDriver$$${CoGroupDriverClassBuilder.nextId};"),
      Option(CoGroupDriverClassBuilder.signature(groupingKeyType)),
      classOf[CoGroupDriver[_, _]].asType)
    with Branching {

  override def defConstructors(ctorDef: ConstructorDef): Unit = {
    ctorDef.newInit(Seq(classOf[SparkContext].asType, classOf[Seq[_]].asType, classOf[Partitioner].asType, classOf[Ordering[_]].asType)) { mb =>
      import mb._
      val scVar = `var`(classOf[SparkContext].asType, thisVar.nextLocal)
      val inputsVar = `var`(classOf[Seq[_]].asType, scVar.nextLocal)
      val partVar = `var`(classOf[Partitioner].asType, inputsVar.nextLocal)
      val groupingVar = `var`(classOf[Ordering[_]].asType, partVar.nextLocal)
      thisVar.push().invokeInit(superType, scVar.push(), inputsVar.push(), partVar.push(), groupingVar.push())

      initFields(mb)
    }
  }
}

object CoGroupDriverClassBuilder {

  private[this] val curId: AtomicLong = new AtomicLong(0L)

  def nextId: Long = curId.getAndIncrement

  def signature(groupingKeyType: Type): String = {
    new ClassSignatureBuilder()
      .newSuperclass {
        _.newClassType(classOf[CoGroupDriver[_, _]].asType) {
          _
            .newTypeArgument(SignatureVisitor.INSTANCEOF, Type.LONG_TYPE.boxed)
            .newTypeArgument(SignatureVisitor.INSTANCEOF, groupingKeyType)
        }
      }
      .build()
  }
}