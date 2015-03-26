package com.asakusafw.spark.compiler.operator
package user.join

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import java.nio.file.Files
import java.util.{ List => JList }

import scala.collection.JavaConversions._

import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.testing.MockJobflowProcessorContext
import com.asakusafw.lang.compiler.model.PropertyName
import com.asakusafw.lang.compiler.model.description._
import com.asakusafw.lang.compiler.model.graph.Group
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.runtime.core.Result
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value._
import com.asakusafw.spark.compiler.spi.{ OperatorCompiler, OperatorType }
import com.asakusafw.spark.runtime.fragment._
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.operator.{ MasterCheck, MasterSelection }

@RunWith(classOf[JUnitRunner])
class MasterCheckOperatorCompilerSpecTest extends MasterCheckOperatorCompilerSpec

class MasterCheckOperatorCompilerSpec extends FlatSpec with LoadClassSugar {

  import MasterCheckOperatorCompilerSpec._

  behavior of classOf[MasterCheckOperatorCompiler].getSimpleName

  it should "compile MasterCheck operator without master selection" in {
    val operator = OperatorExtractor
      .extract(classOf[MasterCheck], classOf[MasterCheckOperator], "check")
      .input("hoges", ClassDescription.of(classOf[Hoge]),
        new Group(Seq(PropertyName.of("id")), Seq.empty[Group.Ordering]))
      .input("foos", ClassDescription.of(classOf[Foo]),
        new Group(
          Seq(PropertyName.of("hogeId")),
          Seq(new Group.Ordering(PropertyName.of("id"), Group.Direction.ASCENDANT))))
      .output("found", ClassDescription.of(classOf[Foo]))
      .output("missed", ClassDescription.of(classOf[Foo]))
      .build()

    val classpath = Files.createTempDirectory("MasterCheckOperatorCompilerSpec").toFile
    implicit val context = OperatorCompiler.Context(
      flowId = "flowId",
      jpContext = new MockJobflowProcessorContext(
        new CompilerOptions("buildid", "", Map.empty[String, String]),
        Thread.currentThread.getContextClassLoader,
        classpath))

    val thisType = OperatorCompiler.compile(operator, OperatorType.CoGroupType)
    val cls = loadClass(thisType.getClassName, classpath).asSubclass(classOf[Fragment[Seq[Iterable[_]]]])

    val (found, missed) = {
      val builder = new OutputFragmentClassBuilder(context.flowId, classOf[Foo].asType)
      val cls = loadClass(builder.thisType.getClassName, builder.build()).asSubclass(classOf[OutputFragment[Foo]])
      (cls.newInstance(), cls.newInstance())
    }

    val fragment = cls.getConstructor(
      classOf[Fragment[_]], classOf[Fragment[_]])
      .newInstance(found, missed)

    {
      val hoge = new Hoge()
      hoge.id.modify(1)
      val hoges = Seq(hoge)
      val foo = new Foo()
      foo.id.modify(10)
      foo.hogeId.modify(1)
      val foos = Seq(foo)
      fragment.add(Seq(hoges, foos))
      assert(found.buffer.size === 1)
      assert(found.buffer(0).id.get === 10)
      assert(missed.buffer.size === 0)
    }

    fragment.reset()
    assert(found.buffer.size === 0)
    assert(missed.buffer.size === 0)

    {
      val hoges = Seq.empty[Hoge]
      val foo = new Foo()
      foo.id.modify(10)
      foo.hogeId.modify(1)
      val foos = Seq(foo)
      fragment.add(Seq(hoges, foos))
      assert(found.buffer.size === 0)
      assert(missed.buffer.size === 1)
      assert(missed.buffer(0).id.get === 10)
    }

    fragment.reset()
    assert(found.buffer.size === 0)
    assert(missed.buffer.size === 0)
  }

  it should "compile MasterCheck operator with master selection" in {
    val operator = OperatorExtractor
      .extract(classOf[MasterCheck], classOf[MasterCheckOperator], "checkWithSelection")
      .input("hoges", ClassDescription.of(classOf[Hoge]),
        new Group(Seq(PropertyName.of("id")), Seq.empty[Group.Ordering]))
      .input("foos", ClassDescription.of(classOf[Foo]),
        new Group(
          Seq(PropertyName.of("hogeId")),
          Seq(new Group.Ordering(PropertyName.of("id"), Group.Direction.ASCENDANT))))
      .output("found", ClassDescription.of(classOf[Foo]))
      .output("missed", ClassDescription.of(classOf[Foo]))
      .build()

    val classpath = Files.createTempDirectory("MasterCheckOperatorCompilerSpec").toFile
    implicit val context = OperatorCompiler.Context(
      flowId = "flowId",
      jpContext = new MockJobflowProcessorContext(
        new CompilerOptions("buildid", "", Map.empty[String, String]),
        Thread.currentThread.getContextClassLoader,
        classpath))

    val thisType = OperatorCompiler.compile(operator, OperatorType.CoGroupType)
    val cls = loadClass(thisType.getClassName, classpath).asSubclass(classOf[Fragment[Seq[Iterable[_]]]])

    val (found, missed) = {
      val builder = new OutputFragmentClassBuilder(context.flowId, classOf[Foo].asType)
      val cls = loadClass(builder.thisType.getClassName, builder.build()).asSubclass(classOf[OutputFragment[Foo]])
      (cls.newInstance(), cls.newInstance())
    }

    val fragment = cls.getConstructor(
      classOf[Fragment[_]], classOf[Fragment[_]])
      .newInstance(found, missed)

    {
      val hoge = new Hoge()
      hoge.id.modify(0)
      val hoges = Seq(hoge)
      val foos = (0 until 10).map { i =>
        val foo = new Foo()
        foo.id.modify(i)
        foo.hogeId.modify(0)
        foo
      }
      fragment.add(Seq(hoges, foos))
      assert(found.buffer.size === 5)
      assert(found.buffer.map(_.id.get) === (0 until 10 by 2))
      assert(missed.buffer.size === 5)
      assert(missed.buffer.map(_.id.get) === (1 until 10 by 2))
    }

    fragment.reset()
    assert(found.buffer.size === 0)
    assert(missed.buffer.size === 0)

    {
      val hoges = Seq.empty[Hoge]
      val foo = new Foo()
      foo.id.modify(10)
      foo.hogeId.modify(1)
      val foos = Seq(foo)
      fragment.add(Seq(hoges, foos))
      assert(found.buffer.size === 0)
      assert(missed.buffer.size === 1)
      assert(missed.buffer(0).id.get === 10)
    }

    fragment.reset()
    assert(found.buffer.size === 0)
    assert(missed.buffer.size === 0)
  }
}

object MasterCheckOperatorCompilerSpec {

  class Hoge extends DataModel[Hoge] {

    val id = new IntOption()

    override def reset(): Unit = {
      id.setNull()
    }
    override def copyFrom(other: Hoge): Unit = {
      id.copyFrom(other.id)
    }

    def getIdOption: IntOption = id
  }

  class Foo extends DataModel[Foo] {

    val id = new IntOption()
    val hogeId = new IntOption()

    override def reset(): Unit = {
      id.setNull()
      hogeId.setNull()
    }
    override def copyFrom(other: Foo): Unit = {
      id.copyFrom(other.id)
      hogeId.copyFrom(other.hogeId)
    }

    def getIdOption: IntOption = id
    def getHogeIdOption: IntOption = hogeId
  }

  class MasterCheckOperator {

    @MasterCheck
    def check(hoge: Hoge, foo: Foo): Boolean = ???

    @MasterCheck(selection = "select")
    def checkWithSelection(hoge: Hoge, foo: Foo): Boolean = ???

    @MasterSelection
    def select(hoges: JList[Hoge], foo: Foo): Hoge = {
      if (foo.id.get % 2 == 0) {
        hoges.headOption.orNull
      } else {
        null
      }
    }
  }
}