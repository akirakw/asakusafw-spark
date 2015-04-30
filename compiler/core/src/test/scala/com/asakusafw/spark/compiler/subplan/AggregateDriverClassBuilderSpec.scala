package com.asakusafw.spark.compiler
package subplan

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.concurrent.{ Await, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

import org.apache.hadoop.conf.Configuration
import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD

import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.testing.MockJobflowProcessorContext
import com.asakusafw.lang.compiler.model.PropertyName
import com.asakusafw.lang.compiler.model.description._
import com.asakusafw.lang.compiler.model.graph.{ ExternalInput, Groups, MarkerOperator }
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.lang.compiler.planning.{ PlanBuilder, PlanMarker }
import com.asakusafw.runtime.core.Result
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value._
import com.asakusafw.spark.compiler.planning.{ SubPlanInfo, SubPlanOutputInfo }
import com.asakusafw.spark.compiler.spi.SubPlanCompiler
import com.asakusafw.spark.runtime.driver._
import com.asakusafw.spark.runtime.orderings._
import com.asakusafw.spark.runtime.rdd.BranchKey
import com.asakusafw.vocabulary.flow.processor.PartialAggregation
import com.asakusafw.vocabulary.operator.Fold

@RunWith(classOf[JUnitRunner])
class AggregateDriverClassBuilderSpecTest extends AggregateDriverClassBuilderSpec

class AggregateDriverClassBuilderSpec extends FlatSpec with SparkWithClassServerSugar {

  import AggregateDriverClassBuilderSpec._

  behavior of classOf[AggregateDriverClassBuilder].getSimpleName

  def resolvers = SubPlanCompiler(Thread.currentThread.getContextClassLoader)

  it should "build aggregate driver class" in {
    val hogesMarker = MarkerOperator.builder(ClassDescription.of(classOf[Hoge]))
      .attribute(classOf[PlanMarker], PlanMarker.CHECKPOINT).build()

    val operator = OperatorExtractor
      .extract(classOf[Fold], classOf[FoldOperator], "fold")
      .input("hoges", ClassDescription.of(classOf[Hoge]), hogesMarker.getOutput)
      .output("result", ClassDescription.of(classOf[Hoge]))
      .argument("n", ImmediateDescription.of(10))
      .build()

    val resultMarker = MarkerOperator.builder(ClassDescription.of(classOf[Hoge]))
      .attribute(classOf[PlanMarker], PlanMarker.CHECKPOINT).build()
    operator.findOutput("result").connect(resultMarker.getInput)

    val plan = PlanBuilder.from(Seq(operator))
      .add(
        Seq(hogesMarker),
        Seq(resultMarker)).build().getPlan()
    assert(plan.getElements.size === 1)
    val subplan = plan.getElements.head
    subplan.putAttribute(classOf[SubPlanInfo],
      new SubPlanInfo(subplan, SubPlanInfo.DriverType.AGGREGATE, Seq.empty[SubPlanInfo.DriverOption], operator))
    val subplanOutput = subplan.getOutputs.find(_.getOperator.getOriginalSerialNumber == resultMarker.getOriginalSerialNumber).get
    subplanOutput.putAttribute(classOf[SubPlanOutputInfo],
      new SubPlanOutputInfo(subplanOutput, SubPlanOutputInfo.OutputType.AGGREGATED, Seq.empty[SubPlanOutputInfo.OutputOption], Groups.parse(Seq("i")), operator))

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
      broadcastIds = broadcastIdsClassBuilder,
      shuffleKeyTypes = mutable.Set.empty)

    val compiler = resolvers.find(_.support(operator)).get
    val thisType = compiler.compile(subplan)
    context.jpContext.addClass(branchKeysClassBuilder)
    context.jpContext.addClass(broadcastIdsClassBuilder)
    val cls = classServer.loadClass(thisType).asSubclass(classOf[AggregateDriver[Hoge, Hoge]])

    val hoges = sc.parallelize(0 until 10).map { i =>
      val hoge = new Hoge()
      hoge.i.modify(i % 2)
      hoge.sum.modify(i)
      (new ShuffleKey(Seq(hoge.i), Seq.empty) {}, hoge)
    }
    val driver = cls.getConstructor(
      classOf[SparkContext],
      classOf[Broadcast[Configuration]],
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Seq[Future[RDD[(ShuffleKey, _)]]]],
      classOf[Option[ShuffleKey.SortOrdering]],
      classOf[Partitioner])
      .newInstance(
        sc,
        hadoopConf,
        Map.empty,
        Seq(Future.successful(hoges)),
        Option(new ShuffleKey.SortOrdering(1, Array.empty[Boolean])),
        new HashPartitioner(2))
    val results = driver.execute()

    val branchKeyCls = classServer.loadClass(branchKeysClassBuilder.thisType.getClassName)
    def getBranchKey(osn: Long): BranchKey = {
      val sn = subplan.getOperators.toSet.find(_.getOriginalSerialNumber == osn).get.getSerialNumber
      branchKeyCls.getField(branchKeysClassBuilder.getField(sn)).get(null).asInstanceOf[BranchKey]
    }

    assert(driver.branchKeys ===
      Set(resultMarker)
      .map(marker => getBranchKey(marker.getOriginalSerialNumber)))

    val result = Await.result(
      results(getBranchKey(resultMarker.getOriginalSerialNumber)).map(_.asInstanceOf[RDD[(ShuffleKey, Hoge)]]),
      Duration.Inf)
      .collect.toSeq.sortBy(_._1.grouping(0).asInstanceOf[IntOption].get)
    assert(result.size === 2)
    assert(result(0)._1.grouping(0).asInstanceOf[IntOption].get === 0)
    assert(result(0)._2.getSumOption.get === (0 until 10 by 2).sum + 4 * 10)
    assert(result(1)._1.grouping(0).asInstanceOf[IntOption].get === 1)
    assert(result(1)._2.getSumOption.get === (1 until 10 by 2).sum + 4 * 10)
  }
}

object AggregateDriverClassBuilderSpec {

  class Hoge extends DataModel[Hoge] {

    val i = new IntOption()
    val sum = new IntOption()

    override def reset(): Unit = {
      i.setNull()
      sum.setNull()
    }
    override def copyFrom(other: Hoge): Unit = {
      i.copyFrom(other.i)
      sum.copyFrom(other.sum)
    }

    def getIOption: IntOption = i
    def getSumOption: IntOption = sum
  }

  class FoldOperator {

    @Fold(partialAggregation = PartialAggregation.PARTIAL)
    def fold(acc: Hoge, value: Hoge, n: Int): Unit = {
      acc.sum.add(value.sum)
      acc.sum.add(n)
    }
  }
}
