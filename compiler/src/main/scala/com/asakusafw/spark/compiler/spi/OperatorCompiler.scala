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
package spi

import java.util.ServiceLoader

import scala.collection.mutable
import scala.collection.JavaConversions._

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.Operator

sealed trait OperatorType

object OperatorType {

  case object MapType extends OperatorType
  case object CoGroupType extends OperatorType
  case object AggregationType extends OperatorType
}

trait OperatorCompiler {

  def support(
    operator: Operator)(
      implicit context: SparkClientCompiler.Context): Boolean

  def operatorType: OperatorType

  def compile(
    operator: Operator)(
      implicit context: SparkClientCompiler.Context): Type
}

object OperatorCompiler {

  private def getCompiler(
    operator: Operator)(
      implicit context: SparkClientCompiler.Context): Seq[OperatorCompiler] = {
    apply(context.jpContext.getClassLoader).filter(_.support(operator))
  }

  def support(
    operator: Operator,
    operatorType: OperatorType)(
      implicit context: SparkClientCompiler.Context): Boolean = {
    getCompiler(operator).exists(_.operatorType == operatorType)
  }

  def compile(
    operator: Operator,
    operatorType: OperatorType)(
      implicit context: SparkClientCompiler.Context): Type = {
    val compilers = getCompiler(operator).filter(_.operatorType == operatorType)
    assert(compilers.size != 0,
      s"The compiler supporting operator (${operator}, ${operatorType}) is not found.")
    assert(compilers.size == 1,
      "The number of compiler supporting operator "
        + s"(${operator}, ${operatorType}) should be 1: ${compilers.size}")
    compilers.head.compile(operator)
  }

  private[this] val operatorCompilers: mutable.Map[ClassLoader, Seq[OperatorCompiler]] =
    mutable.WeakHashMap.empty

  private[this] def apply(classLoader: ClassLoader): Seq[OperatorCompiler] = {
    operatorCompilers.getOrElse(classLoader, reload(classLoader))
  }

  private[this] def reload(classLoader: ClassLoader): Seq[OperatorCompiler] = {
    val ors = ServiceLoader.load(classOf[OperatorCompiler], classLoader).toSeq
    operatorCompilers(classLoader) = ors
    ors
  }
}
