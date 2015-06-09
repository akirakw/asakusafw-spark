package com.asakusafw.spark.compiler
package subplan

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import java.io.{ DataInput, DataOutput }

import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.concurrent.{ Await, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Writable
import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD

import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.testing.MockJobflowProcessorContext
import com.asakusafw.lang.compiler.model.description._
import com.asakusafw.lang.compiler.model.graph.{ Groups, MarkerOperator }
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.lang.compiler.planning.{ PlanBuilder, PlanMarker }
import com.asakusafw.runtime.core.Result
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value._
import com.asakusafw.spark.compiler.planning.{ SubPlanInfo, SubPlanOutputInfo }
import com.asakusafw.spark.compiler.spi.SubPlanCompiler
import com.asakusafw.spark.runtime.driver._
import com.asakusafw.spark.runtime.rdd.BranchKey
import com.asakusafw.vocabulary.operator.Extract

@RunWith(classOf[JUnitRunner])
class ExtractDriverClassBuilderSpecTest extends ExtractDriverClassBuilderSpec

class ExtractDriverClassBuilderSpec extends FlatSpec with SparkWithClassServerSugar {

  import ExtractDriverClassBuilderSpec._

  behavior of classOf[ExtractDriverClassBuilder].getSimpleName

  it should "build extract driver class" in {
    val hogesMarker = MarkerOperator.builder(ClassDescription.of(classOf[Hoge]))
      .attribute(classOf[PlanMarker], PlanMarker.CHECKPOINT).build()

    val operator = OperatorExtractor
      .extract(classOf[Extract], classOf[ExtractOperator], "extract")
      .input("hogeList", ClassDescription.of(classOf[Hoge]), hogesMarker.getOutput)
      .output("hogeResult", ClassDescription.of(classOf[Hoge]))
      .output("fooResult", ClassDescription.of(classOf[Foo]))
      .output("nResult", ClassDescription.of(classOf[N]))
      .argument("n", ImmediateDescription.of(10))
      .build()

    val hogeResultMarker = MarkerOperator.builder(ClassDescription.of(classOf[Hoge]))
      .attribute(classOf[PlanMarker], PlanMarker.CHECKPOINT).build()
    operator.findOutput("hogeResult").connect(hogeResultMarker.getInput)

    val fooResultMarker = MarkerOperator.builder(ClassDescription.of(classOf[Foo]))
      .attribute(classOf[PlanMarker], PlanMarker.CHECKPOINT).build()
    operator.findOutput("fooResult").connect(fooResultMarker.getInput)

    val nResultMarker = MarkerOperator.builder(ClassDescription.of(classOf[N]))
      .attribute(classOf[PlanMarker], PlanMarker.CHECKPOINT).build()
    operator.findOutput("nResult").connect(nResultMarker.getInput)

    val plan = PlanBuilder.from(Seq(operator))
      .add(
        Seq(hogesMarker),
        Seq(hogeResultMarker, fooResultMarker,
          nResultMarker)).build().getPlan()
    assert(plan.getElements.size === 1)
    val subplan = plan.getElements.head
    subplan.putAttribute(classOf[SubPlanInfo],
      new SubPlanInfo(subplan, SubPlanInfo.DriverType.EXTRACT, Seq.empty[SubPlanInfo.DriverOption], operator))

    val hogeResultOutput = subplan.getOutputs.find(_.getOperator.getOriginalSerialNumber == hogeResultMarker.getOriginalSerialNumber).get
    hogeResultOutput.putAttribute(classOf[SubPlanOutputInfo],
      new SubPlanOutputInfo(hogeResultOutput, SubPlanOutputInfo.OutputType.DONT_CARE, Seq.empty[SubPlanOutputInfo.OutputOption], null, null))

    val fooResultOutput = subplan.getOutputs.find(_.getOperator.getOriginalSerialNumber == fooResultMarker.getOriginalSerialNumber).get
    fooResultOutput.putAttribute(classOf[SubPlanOutputInfo],
      new SubPlanOutputInfo(fooResultOutput, SubPlanOutputInfo.OutputType.PARTITIONED, Seq.empty[SubPlanOutputInfo.OutputOption], Groups.parse(Seq("hogeId"), Seq("-id")), null))

    val nResultOutput = subplan.getOutputs.find(_.getOperator.getOriginalSerialNumber == nResultMarker.getOriginalSerialNumber).get
    nResultOutput.putAttribute(classOf[SubPlanOutputInfo],
      new SubPlanOutputInfo(hogeResultOutput, SubPlanOutputInfo.OutputType.DONT_CARE, Seq.empty[SubPlanOutputInfo.OutputOption], null, null))

    val branchKeysClassBuilder = new BranchKeysClassBuilder("flowId")
    val broadcastIdsClassBuilder = new BroadcastIdsClassBuilder("flowId")
    implicit val context = SubPlanCompiler.Context(
      flowId = "flowId",
      jpContext = new MockJobflowProcessorContext(
        new CompilerOptions("buildid", "", Map.empty[String, String]),
        Thread.currentThread.getContextClassLoader,
        classServer.root.toFile),
      externalInputs = mutable.Map.empty,
      branchKeys = branchKeysClassBuilder,
      broadcastIds = broadcastIdsClassBuilder)

    val compiler = SubPlanCompiler(subplan.getAttribute(classOf[SubPlanInfo]).getDriverType)
    val thisType = compiler.compile(subplan)
    context.jpContext.addClass(branchKeysClassBuilder)
    context.jpContext.addClass(broadcastIdsClassBuilder)
    val cls = classServer.loadClass(thisType).asSubclass(classOf[ExtractDriver[_]])

    val hoges = sc.parallelize(0 until 10).map { i =>
      val hoge = new Hoge()
      hoge.id.modify(i)
      ((), hoge)
    }
    val driver = cls.getConstructor(
      classOf[SparkContext],
      classOf[Broadcast[Configuration]],
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Seq[Future[RDD[_]]]])
      .newInstance(
        sc,
        hadoopConf,
        Map.empty,
        Seq(Future.successful(hoges)))
    val results = driver.execute()

    val branchKeyCls = classServer.loadClass(branchKeysClassBuilder.thisType.getClassName)
    def getBranchKey(osn: Long): BranchKey = {
      val sn = subplan.getOperators.toSet.find(_.getOriginalSerialNumber == osn).get.getSerialNumber
      branchKeyCls.getField(branchKeysClassBuilder.getField(sn)).get(null).asInstanceOf[BranchKey]
    }

    assert(driver.branchKeys ===
      Set(hogeResultMarker, fooResultMarker,
        nResultMarker).map(marker => getBranchKey(marker.getOriginalSerialNumber)))

    val hogeResult = Await.result(
      results(getBranchKey(hogeResultMarker.getOriginalSerialNumber)).map {
        _.map(_._2.asInstanceOf[Hoge]).map(_.id.get)
      }, Duration.Inf)
      .collect.toSeq
    assert(hogeResult.size === 10)
    assert(hogeResult === (0 until 10))

    val fooResult = Await.result(
      results(getBranchKey(fooResultMarker.getOriginalSerialNumber)).map {
        _.map(_._2.asInstanceOf[Foo]).mapPartitionsWithIndex({
          case (part, iter) => iter.map(foo => (part, foo.hogeId.get, foo.id.get))
        })
      }, Duration.Inf)
      .collect.toSeq
    assert(fooResult.size === 45)
    fooResult.groupBy(_._2).foreach {
      case (hogeId, foos) =>
        val part = foos.head._1
        assert(foos.tail.forall(_._1 == part))
        assert(foos.map(_._3) === (0 until hogeId).map(j => (hogeId * (hogeId - 1)) / 2 + j).reverse)
    }

    val nResult = Await.result(
      results(getBranchKey(nResultMarker.getOriginalSerialNumber)).map {
        _.map(_._2.asInstanceOf[N]).map(_.n.get)
      }, Duration.Inf).collect.toSeq
    assert(nResult.size === 10)
    nResult.foreach(n => assert(n === 10))
  }

  it should "build extract driver class missing port connection" in {
    val hogesMarker = MarkerOperator.builder(ClassDescription.of(classOf[Hoge]))
      .attribute(classOf[PlanMarker], PlanMarker.CHECKPOINT).build()

    val operator = OperatorExtractor
      .extract(classOf[Extract], classOf[ExtractOperator], "extract")
      .input("hogeList", ClassDescription.of(classOf[Hoge]), hogesMarker.getOutput)
      .output("hogeResult", ClassDescription.of(classOf[Hoge]))
      .output("fooResult", ClassDescription.of(classOf[Foo]))
      .output("nResult", ClassDescription.of(classOf[N]))
      .argument("n", ImmediateDescription.of(10))
      .build()

    val hogeResultMarker = MarkerOperator.builder(ClassDescription.of(classOf[Hoge]))
      .attribute(classOf[PlanMarker], PlanMarker.CHECKPOINT).build()
    operator.findOutput("hogeResult").connect(hogeResultMarker.getInput)

    val plan = PlanBuilder.from(Seq(operator))
      .add(
        Seq(hogesMarker),
        Seq(hogeResultMarker)).build().getPlan()
    assert(plan.getElements.size === 1)
    val subplan = plan.getElements.head
    subplan.putAttribute(classOf[SubPlanInfo],
      new SubPlanInfo(subplan, SubPlanInfo.DriverType.EXTRACT, Seq.empty[SubPlanInfo.DriverOption], operator))

    val hogeResultOutput = subplan.getOutputs.find(_.getOperator.getOriginalSerialNumber == hogeResultMarker.getOriginalSerialNumber).get
    hogeResultOutput.putAttribute(classOf[SubPlanOutputInfo],
      new SubPlanOutputInfo(hogeResultOutput, SubPlanOutputInfo.OutputType.DONT_CARE, Seq.empty[SubPlanOutputInfo.OutputOption], null, null))

    val branchKeysClassBuilder = new BranchKeysClassBuilder("flowId")
    val broadcastIdsClassBuilder = new BroadcastIdsClassBuilder("flowId")
    implicit val context = SubPlanCompiler.Context(
      flowId = "flowId",
      jpContext = new MockJobflowProcessorContext(
        new CompilerOptions("buildid", "", Map.empty[String, String]),
        Thread.currentThread.getContextClassLoader,
        classServer.root.toFile),
      externalInputs = mutable.Map.empty,
      branchKeys = branchKeysClassBuilder,
      broadcastIds = broadcastIdsClassBuilder)

    val compiler = SubPlanCompiler(subplan.getAttribute(classOf[SubPlanInfo]).getDriverType)
    val thisType = compiler.compile(subplan)
    context.jpContext.addClass(branchKeysClassBuilder)
    context.jpContext.addClass(broadcastIdsClassBuilder)
    val cls = classServer.loadClass(thisType).asSubclass(classOf[ExtractDriver[_]])

    val hoges = sc.parallelize(0 until 10).map { i =>
      val hoge = new Hoge()
      hoge.id.modify(i)
      ((), hoge)
    }
    val driver = cls.getConstructor(
      classOf[SparkContext],
      classOf[Broadcast[Configuration]],
      classOf[Map[Long, Broadcast[_]]],
      classOf[Seq[RDD[_]]])
      .newInstance(
        sc,
        hadoopConf,
        Map.empty,
        Seq(Future.successful(hoges)))
    val results = driver.execute()

    val branchKeyCls = classServer.loadClass(branchKeysClassBuilder.thisType.getClassName)
    def getBranchKey(osn: Long): BranchKey = {
      val sn = subplan.getOperators.toSet.find(_.getOriginalSerialNumber == osn).get.getSerialNumber
      branchKeyCls.getField(branchKeysClassBuilder.getField(sn)).get(null).asInstanceOf[BranchKey]
    }

    assert(driver.branchKeys ===
      Set(hogeResultMarker).map(marker => getBranchKey(marker.getOriginalSerialNumber)))

    val hogeResult = Await.result(
      results(getBranchKey(hogeResultMarker.getOriginalSerialNumber)).map {
        _.map(_._2.asInstanceOf[Hoge]).map(_.id.get)
      }, Duration.Inf)
      .collect.toSeq
    assert(hogeResult.size === 10)
    assert(hogeResult === (0 until 10))
  }
}

object ExtractDriverClassBuilderSpec {

  class Hoge extends DataModel[Hoge] with Writable {

    val id = new IntOption()

    override def reset(): Unit = {
      id.setNull()
    }
    override def copyFrom(other: Hoge): Unit = {
      id.copyFrom(other.id)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
    }

    def getIdOption: IntOption = id
  }

  class Foo extends DataModel[Foo] with Writable {

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
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      hogeId.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      hogeId.write(out)
    }

    def getIdOption: IntOption = id
    def getHogeIdOption: IntOption = hogeId
  }

  class N extends DataModel[N] with Writable {

    val n = new IntOption()

    override def reset(): Unit = {
      n.setNull()
    }
    override def copyFrom(other: N): Unit = {
      n.copyFrom(other.n)
    }
    override def readFields(in: DataInput): Unit = {
      n.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      n.write(out)
    }

    def getNOption: IntOption = n
  }

  class ExtractOperator {

    private[this] val foo = new Foo()

    private[this] val n = new N

    @Extract
    def extract(
      hoge: Hoge,
      hogeResult: Result[Hoge], fooResult: Result[Foo],
      nResult: Result[N], n: Int): Unit = {
      hogeResult.add(hoge)
      for (i <- 0 until hoge.id.get) {
        foo.reset()
        foo.id.modify((hoge.id.get * (hoge.id.get - 1)) / 2 + i)
        foo.hogeId.copyFrom(hoge.id)
        fooResult.add(foo)
      }
      this.n.n.modify(n)
      nResult.add(this.n)
    }
  }
}