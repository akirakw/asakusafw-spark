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
package com.asakusafw.spark.extensions.iterativebatch.compiler
package graph

import org.junit.runner.RunWith
import org.scalatest.{ FlatSpec, Suites }
import org.scalatest.junit.JUnitRunner
import java.io.{ DataInput, DataOutput, File }
import java.util.{ List => JList }

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.{ classTag, ClassTag }
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{ NullWritable, Writable }
import org.apache.hadoop.mapreduce.{ Job => MRJob }
import org.apache.hadoop.mapreduce.lib.input.{ FileInputFormat, SequenceFileInputFormat }
import org.apache.hadoop.mapreduce.lib.output.{ FileOutputFormat, SequenceFileOutputFormat }
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.objectweb.asm.Type
import com.asakusafw.bridge.api.BatchContext
import com.asakusafw.bridge.hadoop.directio.DirectFileInputFormat
import com.asakusafw.bridge.hadoop.temporary.{ TemporaryFileInputFormat, TemporaryFileOutputFormat }
import com.asakusafw.bridge.stage.StageInfo
import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.testing.MockJobflowProcessorContext
import com.asakusafw.lang.compiler.common.Location
import com.asakusafw.lang.compiler.extension.directio.{ DirectFileIoConstants, DirectFileOutputModel }
import com.asakusafw.lang.compiler.hadoop.{ InputFormatInfo, InputFormatInfoExtension }
import com.asakusafw.lang.compiler.inspection.{ AbstractInspectionExtension, InspectionExtension }
import com.asakusafw.lang.compiler.model.description.{ ClassDescription, Descriptions }
import com.asakusafw.lang.compiler.model.graph._
import com.asakusafw.lang.compiler.model.info.{ ExternalInputInfo, ExternalOutputInfo }
import com.asakusafw.lang.compiler.model.iterative.IterativeExtension
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.lang.compiler.planning._
import com.asakusafw.runtime.core.Result
import com.asakusafw.runtime.directio.hadoop.{ HadoopDataSource, SequenceFileFormat }
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value._
import com.asakusafw.spark.compiler.{ ClassServerForAll, FlowIdForEach }
import com.asakusafw.spark.compiler.directio.DirectOutputDescription
import com.asakusafw.spark.compiler.planning.SparkPlanning
import com.asakusafw.spark.runtime._
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.flow.processor.PartialAggregation
import com.asakusafw.vocabulary.model.{ Joined, Key, Summarized }
import com.asakusafw.vocabulary.operator._
import com.asakusafw.spark.extensions.iterativebatch.runtime.IterativeBatchExecutor
import com.asakusafw.spark.extensions.iterativebatch.runtime.graph.IterativeJob

@RunWith(classOf[JUnitRunner])
class IterativeJobCompilerSpecTest extends IterativeJobCompilerSpec

class IterativeJobCompilerSpec extends Suites(
  (for {
    threshold <- Seq(None, Some(0))
    parallelism <- Seq(None, Some(8), Some(0))
  } yield {
    new IterativeJobCompilerSpecBase(threshold, parallelism)
  }): _*)

class IterativeJobCompilerSpecBase(threshold: Option[Int], parallelism: Option[Int])
  extends FlatSpec
  with ClassServerForAll
  with SparkForAll
  with FlowIdForEach
  with TempDirForAll
  with TempDirForEach
  with UsingCompilerContext
  with JobContextSugar
  with RoundContextSugar {

  import IterativeJobCompilerSpec._

  behavior of IterativeJobCompiler.getClass.getSimpleName

  val configuration =
    "master=local[8]" +
      s"${threshold.map(t => s", threshold=${t}").getOrElse("")}" +
      s"${parallelism.map(p => s", parallelism=${p}").getOrElse("")}"

  private var root: File = _

  override def configure(conf: SparkConf): SparkConf = {
    val tmpDir = createTempDirectoryForAll("directio-").toFile()
    val system = new File(tmpDir, "system")
    root = new File(tmpDir, "directio")
    conf.setHadoopConf("com.asakusafw.output.system.dir", system.getAbsolutePath)
    conf.setHadoopConf("com.asakusafw.directio.test", classOf[HadoopDataSource].getName)
    conf.setHadoopConf("com.asakusafw.directio.test.path", "test")
    conf.setHadoopConf("com.asakusafw.directio.test.fs.path", root.getAbsolutePath)

    threshold.foreach(i => conf.set("spark.shuffle.sort.bypassMergeThreshold", i.toString))
    parallelism.foreach(para => conf.set(Props.Parallelism, para.toString))
    super.configure(conf)
  }

  val configurePath: (MRJob, File, String) => Unit = { (job, path, name) =>
    job.setOutputFormatClass(classOf[TemporaryFileOutputFormat[_]])
    FileOutputFormat.setOutputPath(
      job,
      new Path(path.getPath, s"${MockJobflowProcessorContext.EXTERNAL_INPUT_BASE}${name}"))
  }

  def prepareData[T: ClassTag](
    name: String,
    path: File,
    configurePath: (MRJob, File, String) => Unit = configurePath)(
      rdd: RDD[T]): Unit = {
    val job = MRJob.getInstance(sc.hadoopConfiguration)
    job.setOutputKeyClass(classOf[NullWritable])
    job.setOutputValueClass(classTag[T].runtimeClass)
    configurePath(job, path, name)
    rdd.map((NullWritable.get, _)).saveAsNewAPIHadoopDataset(job.getConfiguration)
  }

  def readResult[T: ClassTag](name: String, round: Int, path: File): RDD[T] = {
    val job = MRJob.getInstance(sc.hadoopConfiguration)
    FileInputFormat.setInputPaths(
      job,
      new Path(path.getPath, s"${name}/round_${round}/part-*"))
    sc.newAPIHadoopRDD(
      job.getConfiguration,
      classOf[TemporaryFileInputFormat[T]],
      classOf[NullWritable],
      classTag[T].runtimeClass.asInstanceOf[Class[T]]).map(_._2)
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob from simple plan: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 100).map(Foo.intToFoo)
      }

      val inputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "test",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), inputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val outputOperator = ExternalOutput
        .newInstance("output", roundFoo.findOutput("output"))

      val graph = new OperatorGraph(Seq(inputOperator, outputOperator))

      val jobType = compile(flowId, graph, 2, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        val result = readResult[Foo](outputOperator.getName, round, path)
          .map { foo =>
            (foo.id.get, foo.foo.getAsString)
          }.collect.toSeq.sortBy(_._1)
        assert(result === (0 until 100).map(i => (100 * round + i, s"foo${100 * round + i}")))
      }
    }
  }

  for {
    (iterativeExtension, i) <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round")).zipWithIndex
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob from simple plan with InputFormatInfo: [${conf}]" in {
      val path = new File(root, s"foos${i}")

      val configurePath: (MRJob, File, String) => Unit = { (job, path, name) =>
        job.setOutputFormatClass(classOf[SequenceFileOutputFormat[NullWritable, Foo]])
        FileOutputFormat.setOutputPath(job, new Path(path.getPath, name))
      }

      val rounds = 0 to 1
      for {
        round <- rounds
      } {
        prepareData(s"foos_${round}", path, configurePath) {
          sc.parallelize(0 until 100).map(Foo.intToFoo).map(Foo.round(_, round))
        }
      }

      val inputOperator = ExternalInput
        .newWithAttributes("foos",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "test",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val outputOperator = ExternalOutput
        .newInstance("output", inputOperator.findOutput(ExternalInput.PORT_NAME))

      val graph = new OperatorGraph(Seq(inputOperator, outputOperator))

      val jobType = {
        val jpContext = newJPContext(path, classServer.root.toFile)
        jpContext.registerExtension(
          classOf[InputFormatInfoExtension],
          new InputFormatInfoExtension() {

            override def resolve(name: String, info: ExternalInputInfo): InputFormatInfo = {
              new InputFormatInfo(
                ClassDescription.of(classOf[DirectFileInputFormat]),
                ClassDescription.of(classOf[NullWritable]),
                ClassDescription.of(classOf[Foo]),
                Map(
                  DirectFileInputFormat.KEY_BASE_PATH -> s"test/foos${i}",
                  DirectFileInputFormat.KEY_RESOURCE_PATH -> "foos_${round}/part-*",
                  DirectFileInputFormat.KEY_DATA_CLASS -> classOf[Foo].getName,
                  DirectFileInputFormat.KEY_FORMAT_CLASS -> classOf[FooSequenceFileFormat].getName))
            }
          })
        val jobflow = newJobflow(flowId, graph)
        val plan = SparkPlanning.plan(jpContext, jobflow).getPlan
        assert(plan.getElements.size === 2)

        implicit val context = newIterativeJobCompilerContext(flowId, jpContext)
        val jobType = IterativeJobCompiler.compile(plan)
        context.addClass(context.branchKeys)
        context.addClass(context.broadcastIds)
        jobType
      }

      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        val result = readResult[Foo](outputOperator.getName, round, path)
          .map { foo =>
            (foo.id.get, foo.foo.getAsString)
          }.collect.toSeq.sortBy(_._1)
        assert(result === (0 until 100).map(i => (100 * round + i, s"foo${100 * round + i}")))
      }
    }
  }

  for {
    (iterativeExtension, i) <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round")).zipWithIndex
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile Job from simple plan with DirectOutput flat: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foo", path) {
        sc.parallelize(0 until 100).map(Foo.intToFoo)
      }

      val inputOperator = ExternalInput
        .newInstance("foo/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "test",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), inputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val outputOperator = ExternalOutput
        .builder("output",
          new ExternalOutputInfo.Basic(
            ClassDescription.of(classOf[DirectFileOutputModel]),
            DirectFileIoConstants.MODULE_NAME,
            ClassDescription.of(classOf[Foo]),
            Descriptions.valueOf(
              new DirectFileOutputModel(
                DirectOutputDescription(
                  basePath = s"test/flat${i}_$${round}",
                  resourcePattern = "*.bin",
                  order = Seq.empty,
                  deletePatterns = Seq("*.bin"),
                  formatType = classOf[FooSequenceFileFormat])))))
        .input(ExternalOutput.PORT_NAME, ClassDescription.of(classOf[Foo]), roundFoo.findOutput("output"))
        .build()

      val graph = new OperatorGraph(Seq(inputOperator, outputOperator))

      val jobType = compile(flowId, graph, 2, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        val result = sc.newAPIHadoopFile[NullWritable, Foo, SequenceFileInputFormat[NullWritable, Foo]](
          new File(root, s"flat${i}_${round}/*.bin").getAbsolutePath)
          .map(_._2)
          .map { foo =>
            (foo.id.get, foo.foo.getAsString)
          }.collect.toSeq.sortBy(_._1)
        assert(result === (0 until 100).map(i => (100 * round + i, s"foo${100 * round + i}")))
      }
    }
  }

  for {
    (iterativeExtension, i) <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round")).zipWithIndex
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile Job from simple plan with DirectOutput group: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("baz", path) {
        sc.parallelize(0 until 100).map(Baz.intToBaz)
      }

      val inputOperator = ExternalInput
        .newInstance("baz/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Baz]),
            "test",
            ClassDescription.of(classOf[Baz]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBaz = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBaz")
        .input("baz", ClassDescription.of(classOf[Baz]), inputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Baz]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val outputOperator = ExternalOutput
        .builder("output",
          new ExternalOutputInfo.Basic(
            ClassDescription.of(classOf[DirectFileOutputModel]),
            DirectFileIoConstants.MODULE_NAME,
            ClassDescription.of(classOf[Baz]),
            Descriptions.valueOf(
              new DirectFileOutputModel(
                DirectOutputDescription(
                  basePath = s"test/group${i}",
                  resourcePattern = "baz_{id}.bin",
                  order = Seq("-n"),
                  deletePatterns = Seq("*.bin"),
                  formatType = classOf[BazSequenceFileFormat])))))
        .input(ExternalOutput.PORT_NAME, ClassDescription.of(classOf[Baz]), roundBaz.findOutput("output"))
        .build()

      val graph = new OperatorGraph(Seq(inputOperator, outputOperator))

      val jobType = compile(flowId, graph, 2, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val result = sc.newAPIHadoopFile[NullWritable, Baz, SequenceFileInputFormat[NullWritable, Baz]](
            new File(root, s"group${i}/baz_${100 * round + 0}.bin").getAbsolutePath)
            .map(_._2)
            .map { baz =>
              (baz.id.get, baz.n.get)
            }.collect.toSeq
          assert(result === (0 until 100 by 2).reverse.map(i => (100 * round + 0, 100 * round + i * 100)))
        }
        {
          val result = sc.newAPIHadoopFile[NullWritable, Baz, SequenceFileInputFormat[NullWritable, Baz]](
            new File(root, s"group${i}/baz_${100 * round + 1}.bin").getAbsolutePath)
            .map(_._2)
            .map { baz =>
              (baz.id.get, baz.n.get)
            }.collect.toSeq
          assert(result === (1 until 100 by 2).reverse.map(i => (100 * round + 1, 100 * round + i * 100)))
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with Logging: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 100).map(Foo.intToFoo)
      }

      val inputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "test",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), inputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val loggingOperator = OperatorExtractor
        .extract(classOf[Logging], classOf[Ops], "logging")
        .input("foo", ClassDescription.of(classOf[Foo]), roundFoo.findOutput("output"))
        .output("output", ClassDescription.of(classOf[Foo]))
        .build()

      val outputOperator = ExternalOutput
        .newInstance("output", loggingOperator.findOutput("output"))

      val graph = new OperatorGraph(Seq(inputOperator, loggingOperator, outputOperator))

      val jobType = compile(flowId, graph, 2, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        val result = readResult[Foo](outputOperator.getName, round, path)
          .map { foo =>
            (foo.id.get, foo.foo.getAsString)
          }.collect.toSeq.sortBy(_._1)
        assert(result === (0 until 100).map(i => (100 * round + i, s"foo${100 * round + i}")))
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with Extract: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 100).map(Foo.intToFoo)
      }

      val inputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "test",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), inputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val extractOperator = OperatorExtractor
        .extract(classOf[Extract], classOf[Ops], "extract")
        .input("foo", ClassDescription.of(classOf[Foo]), roundFoo.findOutput("output"))
        .output("evenResult", ClassDescription.of(classOf[Foo]))
        .output("oddResult", ClassDescription.of(classOf[Foo]))
        .build()

      val evenOutputOperator = ExternalOutput
        .newInstance("even", extractOperator.findOutput("evenResult"))

      val oddOutputOperator = ExternalOutput
        .newInstance("odd", extractOperator.findOutput("oddResult"))

      val graph = new OperatorGraph(Seq(
        inputOperator,
        extractOperator,
        evenOutputOperator,
        oddOutputOperator))

      val jobType = compile(flowId, graph, 3, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val result = readResult[Foo](evenOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(result ===
            (0 until 100).filter(_ % 2 == 0).map(i => (100 * round + i, s"foo${100 * round + i}")))
        }
        {
          val result = readResult[Foo](oddOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(result ===
            (0 until 100).filterNot(_ % 2 == 0).map(i => (100 * round + i, s"foo${100 * round + i}")))
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with Checkpoint and Extract: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 100).map(Foo.intToFoo)
      }

      val inputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "test",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), inputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val checkpointOperator = CoreOperator
        .builder(CoreOperator.CoreOperatorKind.CHECKPOINT)
        .input("input", ClassDescription.of(classOf[Foo]), roundFoo.findOutput("output"))
        .output("output", ClassDescription.of(classOf[Foo]))
        .build()

      val extractOperator = OperatorExtractor
        .extract(classOf[Extract], classOf[Ops], "extract")
        .input("foo", ClassDescription.of(classOf[Foo]), checkpointOperator.findOutput("output"))
        .output("evenResult", ClassDescription.of(classOf[Foo]))
        .output("oddResult", ClassDescription.of(classOf[Foo]))
        .build()

      val evenOutputOperator = ExternalOutput
        .newInstance("even", extractOperator.findOutput("evenResult"))

      val oddOutputOperator = ExternalOutput
        .newInstance("odd", extractOperator.findOutput("oddResult"))

      val graph = new OperatorGraph(Seq(
        inputOperator,
        checkpointOperator,
        extractOperator,
        evenOutputOperator,
        oddOutputOperator))

      val jobType = compile(flowId, graph, 4, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val result = readResult[Foo](evenOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(result ===
            (0 until 100).filter(_ % 2 == 0).map(i => (100 * round + i, s"foo${100 * round + i}")))
        }
        {
          val result = readResult[Foo](oddOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(result ===
            (0 until 100).filterNot(_ % 2 == 0).map(i => (100 * round + i, s"foo${100 * round + i}")))
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with StopFragment: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 100).map(Foo.intToFoo)
      }

      val inputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "test",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), inputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val extractOperator = OperatorExtractor
        .extract(classOf[Extract], classOf[Ops], "extract")
        .input("foo", ClassDescription.of(classOf[Foo]), roundFoo.findOutput("output"))
        .output("evenResult", ClassDescription.of(classOf[Foo]))
        .output("oddResult", ClassDescription.of(classOf[Foo]))
        .build()

      val evenOutputOperator = ExternalOutput
        .newInstance("even", extractOperator.findOutput("evenResult"))

      val graph = new OperatorGraph(Seq(
        inputOperator,
        extractOperator,
        evenOutputOperator))

      val jobType = compile(flowId, graph, 2, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val result = readResult[Foo](evenOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(result ===
            (0 until 100).filter(_ % 2 == 0).map(i => (100 * round + i, s"foo${100 * round + i}")))
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with CoGroup: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos1", path) {
        sc.parallelize(0 until 5).map(Foo.intToFoo)
      }
      prepareData("foos2", path) {
        sc.parallelize(5 until 10).map(Foo.intToFoo)
      }
      prepareData("bars", path) {
        sc.parallelize(0 until 10).flatMap(Bar.intToBars)
      }

      val foo1InputOperator = ExternalInput
        .newInstance("foos1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos1",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), foo1InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val foo2InputOperator = ExternalInput
        .newInstance("foos2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos2",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo2 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), foo2InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val cogroupOperator = OperatorExtractor
        .extract(classOf[CoGroup], classOf[Ops], "cogroup")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo1.findOutput("output"), roundFoo2.findOutput("output"))
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("fooResult", ClassDescription.of(classOf[Foo]))
        .output("barResult", ClassDescription.of(classOf[Bar]))
        .output("fooError", ClassDescription.of(classOf[Foo]))
        .output("barError", ClassDescription.of(classOf[Bar]))
        .build()

      val fooResultOutputOperator = ExternalOutput
        .newInstance("fooResult", cogroupOperator.findOutput("fooResult"))

      val barResultOutputOperator = ExternalOutput
        .newInstance("barResult", cogroupOperator.findOutput("barResult"))

      val fooErrorOutputOperator = ExternalOutput
        .newInstance("fooError", cogroupOperator.findOutput("fooError"))

      val barErrorOutputOperator = ExternalOutput
        .newInstance("barError", cogroupOperator.findOutput("barError"))

      val graph = new OperatorGraph(Seq(
        foo1InputOperator, foo2InputOperator, barInputOperator,
        cogroupOperator,
        fooResultOutputOperator, barResultOutputOperator, fooErrorOutputOperator, barErrorOutputOperator))

      val jobType = compile(flowId, graph, 8, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val fooResult = readResult[Foo](fooResultOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(fooResult.size === 1)
          assert(fooResult(0) === (100 * round + 1, s"foo${100 * round + 1}"))
        }
        {
          val barResult = readResult[Bar](barResultOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(barResult.size === 1)
          assert(barResult(0) === (100 * round + 10, 100 * round + 1, s"bar${100 * round + 10}"))
        }
        {
          val fooError = readResult[Foo](fooErrorOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(fooError.size === 9)
          assert(fooError(0) === (100 * round + 0, s"foo${100 * round + 0}"))
          for (i <- 2 until 10) {
            assert(fooError(i - 1) === (100 * round + i, s"foo${100 * round + i}"))
          }
        }
        {
          val barError = readResult[Bar](barErrorOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(bar => (bar._2, bar._1))
          assert(barError.size === 44)
          for {
            i <- 2 until 10
            j <- 0 until i
          } {
            assert(barError((i * (i - 1)) / 2 + j - 1) ===
              (100 * round + 10 + j, 100 * round + i, s"bar${100 * round + 10 + j}"))
          }
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with CoGroup with grouping is empty: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos1", path) {
        sc.parallelize(0 until 5).map(Foo.intToFoo)
      }
      prepareData("foos2", path) {
        sc.parallelize(5 until 10).map(Foo.intToFoo)
      }
      prepareData("bars", path) {
        sc.parallelize(0 until 10).flatMap(Bar.intToBars)
      }

      val foo1InputOperator = ExternalInput
        .newInstance("foos1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos1",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), foo1InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val foo2InputOperator = ExternalInput
        .newInstance("foos2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos2",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo2 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), foo2InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val cogroupOperator = OperatorExtractor
        .extract(classOf[CoGroup], classOf[Ops], "cogroup")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq.empty[String]),
          roundFoo1.findOutput("output"), roundFoo2.findOutput("output"))
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq.empty[String], Seq("+id")),
          roundBar.findOutput("output"))
        .output("fooResult", ClassDescription.of(classOf[Foo]))
        .output("barResult", ClassDescription.of(classOf[Bar]))
        .output("fooError", ClassDescription.of(classOf[Foo]))
        .output("barError", ClassDescription.of(classOf[Bar]))
        .build()

      val fooResultOutputOperator = ExternalOutput
        .newInstance("fooResult", cogroupOperator.findOutput("fooResult"))

      val barResultOutputOperator = ExternalOutput
        .newInstance("barResult", cogroupOperator.findOutput("barResult"))

      val fooErrorOutputOperator = ExternalOutput
        .newInstance("fooError", cogroupOperator.findOutput("fooError"))

      val barErrorOutputOperator = ExternalOutput
        .newInstance("barError", cogroupOperator.findOutput("barError"))

      val graph = new OperatorGraph(Seq(
        foo1InputOperator, foo2InputOperator, barInputOperator,
        cogroupOperator,
        fooResultOutputOperator, barResultOutputOperator, fooErrorOutputOperator, barErrorOutputOperator))

      val jobType = compile(flowId, graph, 8, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val fooResult = readResult[Foo](fooResultOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(fooResult.size === 0)
        }
        {
          val barResult = readResult[Bar](barResultOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(barResult.size === 0)
        }
        {
          val fooError = readResult[Foo](fooErrorOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(fooError.size === 10)
          for (i <- 0 until 10) {
            assert(fooError(i) === (100 * round + i, s"foo${100 * round + i}"))
          }
        }
        {
          val barError = readResult[Bar](barErrorOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(bar => (bar._2, bar._1))
          assert(barError.size === 45)
          for {
            i <- 0 until 10
            j <- 0 until i
          } {
            assert(barError((i * (i - 1)) / 2 + j) ===
              (100 * round + 10 + j, 100 * round + i, s"bar${100 * round + 10 + j}"))
          }
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with MasterCheck: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 10).map(Foo.intToFoo)
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val fooInputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), fooInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterCheckOperator = OperatorExtractor
        .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo1.findOutput("output"))
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("found", ClassDescription.of(classOf[Bar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("found", masterCheckOperator.findOutput("found"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterCheckOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        fooInputOperator, barInputOperator,
        masterCheckOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 5, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[Bar](foundOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }

    it should s"compile IterativeJob with MasterCheck with multiple masters: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos1", path) {
        sc.parallelize(0 until 5).map(Foo.intToFoo)
      }
      prepareData("foos2", path) {
        sc.parallelize(5 until 10).map(Foo.intToFoo)
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val foo1InputOperator = ExternalInput
        .newInstance("foos1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos1",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), foo1InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val foo2InputOperator = ExternalInput
        .newInstance("foos2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos2",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo2 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), foo2InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterCheckOperator = OperatorExtractor
        .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo1.findOutput("output"), roundFoo2.findOutput("output"))
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("found", ClassDescription.of(classOf[Bar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("found", masterCheckOperator.findOutput("found"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterCheckOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        foo1InputOperator, foo2InputOperator, barInputOperator,
        masterCheckOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 6, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[Bar](foundOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }

    it should s"compile IterativeJob with MasterCheck with fixed master: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile
      val rounds = 0 to 1

      prepareData("foos1", path) {
        sc.parallelize(0 until 5).map(Foo.intToFoo).flatMap { foo =>
          rounds.iterator.map { round =>
            Foo.round(foo, round)
          }
        }
      }
      prepareData("foos2", path) {
        sc.parallelize(5 until 10).map(Foo.intToFoo).flatMap { foo =>
          rounds.iterator.map { round =>
            Foo.round(foo, round)
          }
        }
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val foo1InputOperator = ExternalInput
        .newInstance("foos1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos1",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val foo2InputOperator = ExternalInput
        .newInstance("foos2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos2",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterCheckOperator = OperatorExtractor
        .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          foo1InputOperator.getOperatorPort, foo2InputOperator.getOperatorPort)
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("found", ClassDescription.of(classOf[Bar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("found", masterCheckOperator.findOutput("found"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterCheckOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        foo1InputOperator, foo2InputOperator, barInputOperator,
        masterCheckOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 6, path, classServer.root.toFile)
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[Bar](foundOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with broadcast MasterCheck: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 10).map(Foo.intToFoo)
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val fooInputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.TINY))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), fooInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterCheckOperator = OperatorExtractor
        .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo.findOutput("output"))
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("found", ClassDescription.of(classOf[Bar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("found", masterCheckOperator.findOutput("found"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterCheckOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        fooInputOperator, barInputOperator,
        masterCheckOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(
        flowId, graph, 4, path, classServer.root.toFile,
        Map("operator.estimator.Update" -> "1.0"))

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[Bar](foundOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }

    it should s"compile IterativeJob with broadcast MasterCheck with multiple masters: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos1", path) {
        sc.parallelize(0 until 5).map(Foo.intToFoo)
      }
      prepareData("foos2", path) {
        sc.parallelize(5 until 10).map(Foo.intToFoo)
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val foo1InputOperator = ExternalInput
        .newInstance("foos1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos1",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.TINY))

      val roundFoo1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo1", ClassDescription.of(classOf[Foo]), foo1InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val foo2InputOperator = ExternalInput
        .newInstance("foos2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos2",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.TINY))

      val roundFoo2 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo2", ClassDescription.of(classOf[Foo]), foo2InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterCheckOperator = OperatorExtractor
        .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo1.findOutput("output"), roundFoo2.findOutput("output"))
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("found", ClassDescription.of(classOf[Bar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("found", masterCheckOperator.findOutput("found"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterCheckOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        foo1InputOperator, foo2InputOperator, barInputOperator,
        masterCheckOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(
        flowId, graph, 5, path, classServer.root.toFile,
        Map("operator.estimator.Update" -> "1.0"))

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[Bar](foundOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }

    it should s"compile IterativeJob with Checkpoint and broadcast MasterCheck: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 10).map(Foo.intToFoo)
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val fooInputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.TINY))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), fooInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val checkpointOperator = CoreOperator
        .builder(CoreOperator.CoreOperatorKind.CHECKPOINT)
        .input("input", ClassDescription.of(classOf[Bar]), roundBar.findOutput("output"))
        .output("output", ClassDescription.of(classOf[Bar]))
        .build()

      val masterCheckOperator = OperatorExtractor
        .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo.findOutput("output"))
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          checkpointOperator.findOutput("output"))
        .output("found", ClassDescription.of(classOf[Bar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("found", masterCheckOperator.findOutput("found"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterCheckOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        fooInputOperator, barInputOperator,
        masterCheckOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 5, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[Bar](foundOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }

    it should s"compile IterativeJob with broadcast MasterCheck with fixed master: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile
      val rounds = 0 to 1

      prepareData("foos", path) {
        sc.parallelize(0 until 10).map(Foo.intToFoo).flatMap { foo =>
          rounds.iterator.map { round =>
            Foo.round(foo, round)
          }
        }
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val fooInputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.TINY))

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterCheckOperator = OperatorExtractor
        .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          fooInputOperator.getOperatorPort)
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("found", ClassDescription.of(classOf[Bar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("found", masterCheckOperator.findOutput("found"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterCheckOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        fooInputOperator, barInputOperator,
        masterCheckOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 4, path, classServer.root.toFile)
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[Bar](foundOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with MasterJoin: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos1", path) {
        sc.parallelize(0 until 5).map(Foo.intToFoo)
      }
      prepareData("foos2", path) {
        sc.parallelize(5 until 10).map(Foo.intToFoo)
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val foo1InputOperator = ExternalInput
        .newInstance("foos1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos1",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), foo1InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val foo2InputOperator = ExternalInput
        .newInstance("foos2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos2",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundFoo2 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), foo2InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterJoinOperator = OperatorExtractor
        .extract(classOf[MasterJoin], classOf[Ops], "masterjoin")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo1.findOutput("output"), roundFoo2.findOutput("output"))
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("joined", ClassDescription.of(classOf[FooBar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val joinedOutputOperator = ExternalOutput
        .newInstance("joined", masterJoinOperator.findOutput("joined"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterJoinOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        foo1InputOperator, foo2InputOperator, barInputOperator,
        masterJoinOperator,
        joinedOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 6, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[FooBar](joinedOutputOperator.getName, round, path)
            .map { foobar =>
              (foobar.id.get, foobar.foo.getAsString, foobar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + i, s"foo${100 * round + i}", s"bar${100 * round + 10 + i}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }

    it should s"compile IterativeJob with MasterJoin with fixed master: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile
      val rounds = 0 to 1

      prepareData("foos1", path) {
        sc.parallelize(0 until 5).map(Foo.intToFoo).flatMap { foo =>
          rounds.iterator.map { round =>
            Foo.round(foo, round)
          }
        }
      }
      prepareData("foos2", path) {
        sc.parallelize(5 until 10).map(Foo.intToFoo).flatMap { foo =>
          rounds.iterator.map { round =>
            Foo.round(foo, round)
          }
        }
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val foo1InputOperator = ExternalInput
        .newInstance("foos1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos1",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val foo2InputOperator = ExternalInput
        .newInstance("foos2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos2",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterJoinOperator = OperatorExtractor
        .extract(classOf[MasterJoin], classOf[Ops], "masterjoin")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          foo1InputOperator.getOperatorPort, foo2InputOperator.getOperatorPort)
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("joined", ClassDescription.of(classOf[FooBar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val joinedOutputOperator = ExternalOutput
        .newInstance("joined", masterJoinOperator.findOutput("joined"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterJoinOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        foo1InputOperator, foo2InputOperator, barInputOperator,
        masterJoinOperator,
        joinedOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 6, path, classServer.root.toFile)
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[FooBar](joinedOutputOperator.getName, round, path)
            .map { foobar =>
              (foobar.id.get, foobar.foo.getAsString, foobar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + i, s"foo${100 * round + i}", s"bar${100 * round + 10 + i}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with broadcast MasterJoin: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 10).map(Foo.intToFoo)
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val fooInputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.TINY))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), fooInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterJoinOperator = OperatorExtractor
        .extract(classOf[MasterJoin], classOf[Ops], "masterjoin")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo.findOutput("output"))
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("joined", ClassDescription.of(classOf[FooBar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("joined", masterJoinOperator.findOutput("joined"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterJoinOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        fooInputOperator, barInputOperator,
        masterJoinOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 4, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[FooBar](foundOutputOperator.getName, round, path)
            .map { foobar =>
              (foobar.id.get, foobar.foo.getAsString, foobar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + i, s"foo${100 * round + i}", s"bar${100 * round + i + 10}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }

    it should s"compile IterativeJob with broadcast MasterJoin with fixed master: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile
      val rounds = 0 to 1

      prepareData(s"foos", path) {
        sc.parallelize(0 until 10).map(Foo.intToFoo).flatMap { foo =>
          rounds.iterator.map { round =>
            Foo.round(foo, round)
          }
        }
      }
      prepareData("bars", path) {
        sc.parallelize(5 until 15).map(Bar.intToBar)
      }

      val fooInputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.TINY))

      val barInputOperator = ExternalInput
        .newInstance("bars/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Bar]),
            "bars",
            ClassDescription.of(classOf[Bar]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBar = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBar")
        .input("bar", ClassDescription.of(classOf[Bar]), barInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Bar]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterJoinOperator = OperatorExtractor
        .extract(classOf[MasterJoin], classOf[Ops], "masterjoin")
        .input("foos", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          fooInputOperator.getOperatorPort)
        .input("bars", ClassDescription.of(classOf[Bar]),
          Groups.parse(Seq("fooId"), Seq("+id")),
          roundBar.findOutput("output"))
        .output("joined", ClassDescription.of(classOf[FooBar]))
        .output("missed", ClassDescription.of(classOf[Bar]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("joined", masterJoinOperator.findOutput("joined"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterJoinOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        fooInputOperator, barInputOperator,
        masterJoinOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 4, path, classServer.root.toFile)
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[FooBar](foundOutputOperator.getName, round, path)
            .map { foobar =>
              (foobar.id.get, foobar.foo.getAsString, foobar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 5)
          assert(found ===
            (5 until 10).map(i => (100 * round + i, s"foo${100 * round + i}", s"bar${100 * round + i + 10}")))
        }
        {
          val missed = readResult[Bar](missedOutputOperator.getName, round, path)
            .map { bar =>
              (bar.id.get, bar.fooId.get, bar.bar.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 5)
          assert(missed ===
            (10 until 15).map(i => (100 * round + 10 + i, 100 * round + i, s"bar${100 * round + 10 + i}")))
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with broadcast self MasterCheck: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("foos", path) {
        sc.parallelize(0 until 10).map(Foo.intToFoo)
      }

      val fooInputOperator = ExternalInput
        .newInstance("foos/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Foo]),
            "foos1",
            ClassDescription.of(classOf[Foo]),
            ExternalInputInfo.DataSize.TINY))

      val roundFoo = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundFoo")
        .input("foo", ClassDescription.of(classOf[Foo]), fooInputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Foo]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val masterCheckOperator = OperatorExtractor
        .extract(classOf[MasterCheck], classOf[Ops], "mastercheck")
        .input("fooms", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo.findOutput("output"))
        .input("foots", ClassDescription.of(classOf[Foo]),
          Groups.parse(Seq("id")),
          roundFoo.findOutput("output"))
        .output("found", ClassDescription.of(classOf[Foo]))
        .output("missed", ClassDescription.of(classOf[Foo]))
        .build()

      val foundOutputOperator = ExternalOutput
        .newInstance("found", masterCheckOperator.findOutput("found"))

      val missedOutputOperator = ExternalOutput
        .newInstance("missed", masterCheckOperator.findOutput("missed"))

      val graph = new OperatorGraph(Seq(
        fooInputOperator,
        masterCheckOperator,
        foundOutputOperator, missedOutputOperator))

      val jobType = compile(flowId, graph, 4, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val found = readResult[Foo](foundOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(found.size === 10)
          assert(found === (0 until 10).map(i => (100 * round + i, s"foo${100 * round + i}")))
        }
        {
          val missed = readResult[Foo](missedOutputOperator.getName, round, path)
            .map { foo =>
              (foo.id.get, foo.foo.getAsString)
            }.collect.toSeq.sortBy(_._1)
          assert(missed.size === 0)
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with Fold: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("bazs1", path) {
        sc.parallelize(0 until 50).map(Baz.intToBaz)
      }
      prepareData("bazs2", path) {
        sc.parallelize(50 until 100).map(Baz.intToBaz)
      }

      val baz1InputOperator = ExternalInput
        .newInstance("bazs1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Baz]),
            "baz1",
            ClassDescription.of(classOf[Baz]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBaz1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBaz")
        .input("baz", ClassDescription.of(classOf[Baz]), baz1InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Baz]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val baz2InputOperator = ExternalInput
        .newInstance("bazs2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Baz]),
            "baz2",
            ClassDescription.of(classOf[Baz]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBaz2 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBaz")
        .input("baz", ClassDescription.of(classOf[Baz]), baz2InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Baz]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val foldOperator = OperatorExtractor
        .extract(classOf[Fold], classOf[Ops], "fold")
        .input("bazs", ClassDescription.of(classOf[Baz]),
          Groups.parse(Seq("id")),
          roundBaz1.findOutput("output"), roundBaz2.findOutput("output"))
        .output("result", ClassDescription.of(classOf[Baz]))
        .build()

      val resultOutputOperator = ExternalOutput
        .newInstance("result", foldOperator.findOutput("result"))

      val graph = new OperatorGraph(Seq(
        baz1InputOperator, baz2InputOperator,
        foldOperator,
        resultOutputOperator))

      val jobType = compile(flowId, graph, 4, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val result = readResult[Baz](resultOutputOperator.getName, round, path)
            .map { baz =>
              (baz.id.get, baz.n.get)
            }.collect.toSeq.sortBy(_._1)
          assert(result.size === 2)
          assert(result(0)._1 === 100 * round + 0)
          assert(result(0)._2 === (0 until 100 by 2).map(i => 100 * round + i * 100).sum)
          assert(result(1)._1 === 100 * round + 1)
          assert(result(1)._2 === (1 until 100 by 2).map(i => 100 * round + i * 100).sum)
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with Fold with grouping is empty: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("bazs1", path) {
        sc.parallelize(0 until 50).map(Baz.intToBaz)
      }
      prepareData("bazs2", path) {
        sc.parallelize(50 until 100).map(Baz.intToBaz)
      }

      val baz1InputOperator = ExternalInput
        .newInstance("bazs1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Baz]),
            "baz1",
            ClassDescription.of(classOf[Baz]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBaz1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBaz")
        .input("baz", ClassDescription.of(classOf[Baz]), baz1InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Baz]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val baz2InputOperator = ExternalInput
        .newInstance("bazs2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Baz]),
            "baz2",
            ClassDescription.of(classOf[Baz]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBaz2 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBaz")
        .input("baz", ClassDescription.of(classOf[Baz]), baz2InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Baz]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val foldOperator = OperatorExtractor
        .extract(classOf[Fold], classOf[Ops], "fold")
        .input("bazs", ClassDescription.of(classOf[Baz]),
          Groups.parse(Seq.empty[String]),
          roundBaz1.findOutput("output"), roundBaz2.findOutput("output"))
        .output("result", ClassDescription.of(classOf[Baz]))
        .build()

      val resultOutputOperator = ExternalOutput
        .newInstance("result", foldOperator.findOutput("result"))

      val graph = new OperatorGraph(Seq(
        baz1InputOperator, baz2InputOperator,
        foldOperator,
        resultOutputOperator))

      val jobType = compile(flowId, graph, 4, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val result = readResult[Baz](resultOutputOperator.getName, round, path)
            .map { baz =>
              (baz.id.get, baz.n.get)
            }.collect.toSeq.sortBy(_._1)
          assert(result.size === 1)
          assert(result(0)._2 === (0 until 100).map(i => 100 * round + i * 100).sum)
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with Summarize: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("bazs1", path) {
        sc.parallelize(0 until 500).map(Baz.intToBaz)
      }
      prepareData("bazs2", path) {
        sc.parallelize(500 until 1000).map(Baz.intToBaz)
      }

      val baz1InputOperator = ExternalInput
        .newInstance("bazs1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Baz]),
            "baz1",
            ClassDescription.of(classOf[Baz]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBaz1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBaz")
        .input("baz", ClassDescription.of(classOf[Baz]), baz1InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Baz]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val baz2InputOperator = ExternalInput
        .newInstance("bazs2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Baz]),
            "baz2",
            ClassDescription.of(classOf[Baz]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBaz2 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBaz")
        .input("baz", ClassDescription.of(classOf[Baz]), baz2InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Baz]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val summarizeOperator = OperatorExtractor
        .extract(classOf[Summarize], classOf[Ops], "summarize")
        .input("bazs", ClassDescription.of(classOf[Baz]),
          Groups.parse(Seq("id")),
          roundBaz1.findOutput("output"), roundBaz2.findOutput("output"))
        .output("result", ClassDescription.of(classOf[SummarizedBaz]))
        .build()

      val resultOutputOperator = ExternalOutput
        .newInstance("result", summarizeOperator.findOutput("result"))

      val graph = new OperatorGraph(Seq(
        baz1InputOperator, baz2InputOperator,
        summarizeOperator,
        resultOutputOperator))

      val jobType = compile(flowId, graph, 4, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val result = readResult[SummarizedBaz](resultOutputOperator.getName, round, path)
            .map { baz =>
              (baz.id.get, baz.sum.get, baz.max.get, baz.min.get, baz.count.get)
            }.collect.toSeq.sortBy(_._1)
          assert(result.size === 2)
          assert(result(0)._1 === 100 * round + 0)
          assert(result(0)._2 === (0 until 1000 by 2).map(i => 100 * round + i * 100).sum)
          assert(result(0)._3 === 100 * round + 99800)
          assert(result(0)._4 === 100 * round + 0)
          assert(result(0)._5 === 500)
          assert(result(1)._1 === 100 * round + 1)
          assert(result(1)._2 === (1 until 1000 by 2).map(i => 100 * round + i * 100).sum)
          assert(result(1)._3 === 100 * round + 99900)
          assert(result(1)._4 === 100 * round + 100)
          assert(result(1)._5 === 500)
        }
      }
    }
  }

  for {
    iterativeExtension <- Seq(
      new IterativeExtension(),
      new IterativeExtension("round"))
  } {
    val conf = s"${configuration}, IterativeExtension: ${iterativeExtension}"

    it should s"compile IterativeJob with Summarize with grouping is empty: [${conf}]" in {
      val path = createTempDirectoryForEach("test-").toFile

      prepareData("bazs1", path) {
        sc.parallelize(0 until 500).map(Baz.intToBaz)
      }
      prepareData("bazs2", path) {
        sc.parallelize(500 until 1000).map(Baz.intToBaz)
      }

      val baz1InputOperator = ExternalInput
        .newInstance("bazs1/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Baz]),
            "baz1",
            ClassDescription.of(classOf[Baz]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBaz1 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBaz")
        .input("baz", ClassDescription.of(classOf[Baz]), baz1InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Baz]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val baz2InputOperator = ExternalInput
        .newInstance("bazs2/part-*",
          new ExternalInputInfo.Basic(
            ClassDescription.of(classOf[Baz]),
            "baz2",
            ClassDescription.of(classOf[Baz]),
            ExternalInputInfo.DataSize.UNKNOWN))

      val roundBaz2 = OperatorExtractor
        .extract(classOf[Update], classOf[Ops], "roundBaz")
        .input("baz", ClassDescription.of(classOf[Baz]), baz2InputOperator.getOperatorPort)
        .output("output", ClassDescription.of(classOf[Baz]))
        .attribute(classOf[IterativeExtension], iterativeExtension)
        .build()

      val summarizeOperator = OperatorExtractor
        .extract(classOf[Summarize], classOf[Ops], "summarize")
        .input("bazs", ClassDescription.of(classOf[Baz]),
          Groups.parse(Seq.empty[String]),
          roundBaz1.findOutput("output"), roundBaz2.findOutput("output"))
        .output("result", ClassDescription.of(classOf[SummarizedBaz]))
        .build()

      val resultOutputOperator = ExternalOutput
        .newInstance("result", summarizeOperator.findOutput("result"))

      val graph = new OperatorGraph(Seq(
        baz1InputOperator, baz2InputOperator,
        summarizeOperator,
        resultOutputOperator))

      val jobType = compile(flowId, graph, 4, path, classServer.root.toFile)

      val rounds = 0 to 1
      executeJob(flowId, jobType)(rounds)

      for {
        round <- rounds
      } {
        {
          val result = readResult[SummarizedBaz](resultOutputOperator.getName, round, path)
            .map { baz =>
              (baz.id.get, baz.sum.get, baz.max.get, baz.min.get, baz.count.get)
            }.collect.toSeq.sortBy(_._1)
          assert(result.size === 1)
          assert(result(0)._2 === (0 until 1000).map(i => 100 * round + i * 100).sum)
          assert(result(0)._3 === 100 * round + 99900)
          assert(result(0)._4 === 100 * round + 0)
          assert(result(0)._5 === 1000)
        }
      }
    }
  }

  def newJPContext(
    path: File,
    classpath: File,
    properties: Map[String, String] = Map.empty): MockJobflowProcessorContext = {
    val jpContext = new MockJobflowProcessorContext(
      new CompilerOptions("buildid", path.getPath, properties),
      Thread.currentThread.getContextClassLoader,
      classpath)
    jpContext.registerExtension(
      classOf[InspectionExtension],
      new AbstractInspectionExtension {

        override def addResource(location: Location) = {
          jpContext.addResourceFile(location)
        }
      })
    jpContext
  }

  def newJobflow(flowId: String, graph: OperatorGraph): Jobflow = {
    new Jobflow(flowId, ClassDescription.of(classOf[IterativeJobCompilerSpec]), graph)
  }

  def compile(
    flowId: String,
    graph: OperatorGraph,
    subplans: Int,
    path: File,
    classpath: File,
    properties: Map[String, String] = Map.empty): Type = {
    val jpContext = newJPContext(path, classpath, properties)
    val jobflow = newJobflow(flowId, graph)
    val plan = SparkPlanning.plan(jpContext, jobflow).getPlan
    assert(plan.getElements.size === subplans)

    implicit val context = newIterativeJobCompilerContext(flowId, jpContext)
    val jobType = IterativeJobCompiler.compile(plan)
    context.addClass(context.branchKeys)
    context.addClass(context.broadcastIds)
    jobType
  }

  def executeJob(flowId: String, jobType: Type)(rounds: Seq[Int]): Unit = {
    val cls = Class.forName(
      jobType.getClassName, true, Thread.currentThread.getContextClassLoader)
      .asSubclass(classOf[IterativeJob])

    implicit val jobContext = newJobContext(sc)

    val job = cls.getConstructor(classOf[JobContext]).newInstance(jobContext)

    val origin = newRoundContext(flowId = flowId)
    val rcs = rounds.map { round =>
      newRoundContext(
        flowId = flowId,
        stageId = s"round_${round}",
        batchArguments = Map("round" -> round.toString))
    }

    val executor = new IterativeBatchExecutor(numSlots = 1)(job)
    try {
      executor.start()
      executor.submitAll(rcs)
    } finally {
      executor.stop(awaitExecution = true, gracefully = true)
    }
    job.commit(origin, rcs)
  }
}

object IterativeJobCompilerSpec {

  class Foo extends DataModel[Foo] with Writable {

    val id = new IntOption()
    val foo = new StringOption()

    override def reset(): Unit = {
      id.setNull()
      foo.setNull()
    }
    override def copyFrom(other: Foo): Unit = {
      id.copyFrom(other.id)
      foo.copyFrom(other.foo)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      foo.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      foo.write(out)
    }

    def getIdOption: IntOption = id
    def getFooOption: StringOption = foo
  }

  object Foo {

    def intToFoo: Int => Foo = {

      lazy val foo = new Foo()

      { i =>
        foo.id.modify(i)
        foo.foo.modify(s"foo${i}")
        foo
      }
    }

    def round(foo: Foo, round: Int): Foo = {
      val id = foo.id.get
      foo.id.modify(100 * round + id)
      foo.foo.modify(s"foo${100 * round + id}")
      foo
    }
  }

  class Bar extends DataModel[Bar] with Writable {

    val id = new IntOption()
    val fooId = new IntOption()
    val bar = new StringOption()

    override def reset(): Unit = {
      id.setNull()
      fooId.setNull()
      bar.setNull()
    }
    override def copyFrom(other: Bar): Unit = {
      id.copyFrom(other.id)
      fooId.copyFrom(other.fooId)
      bar.copyFrom(other.bar)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      fooId.readFields(in)
      bar.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      fooId.write(out)
      bar.write(out)
    }

    def getIdOption: IntOption = id
    def getFooIdOption: IntOption = fooId
    def getBarOption: StringOption = bar
  }

  object Bar {

    def intToBar: Int => Bar = {

      lazy val bar = new Bar()

      { i =>
        bar.id.modify(10 + i)
        bar.fooId.modify(i)
        bar.bar.modify(s"bar${10 + i}")
        bar
      }
    }

    def intToBars: Int => Iterator[Bar] = {

      lazy val bar = new Bar()

      { i =>
        (0 until i).iterator.map { j =>
          bar.id.modify(10 + j)
          bar.fooId.modify(i)
          bar.bar.modify(s"bar${10 + j}")
          bar
        }
      }
    }

    def round(bar: Bar, round: Int): Bar = {
      val id = bar.id.get
      bar.id.modify(100 * round + id)
      bar.fooId.modify(100 * round + bar.fooId.get)
      bar.bar.modify(s"bar${100 * round + id}")
      bar
    }
  }

  @Joined(terms = Array(
    new Joined.Term(source = classOf[Foo], shuffle = new Key(group = Array("id")), mappings = Array(
      new Joined.Mapping(source = "id", destination = "id"),
      new Joined.Mapping(source = "foo", destination = "foo"))),
    new Joined.Term(source = classOf[Bar], shuffle = new Key(group = Array("fooId")), mappings = Array(
      new Joined.Mapping(source = "fooId", destination = "id"),
      new Joined.Mapping(source = "bar", destination = "bar")))))
  class FooBar extends DataModel[FooBar] with Writable {

    val id = new IntOption()
    val foo = new StringOption()
    val bar = new StringOption()

    override def reset(): Unit = {
      id.setNull()
      foo.setNull()
      bar.setNull()
    }
    override def copyFrom(other: FooBar): Unit = {
      id.copyFrom(other.id)
      foo.copyFrom(other.foo)
      bar.copyFrom(other.bar)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      foo.readFields(in)
      bar.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      foo.write(out)
      bar.write(out)
    }

    def getIdOption: IntOption = id
    def getFooOption: StringOption = foo
    def getBarOption: StringOption = bar
  }

  class Baz extends DataModel[Baz] with Writable {

    val id = new IntOption()
    val n = new IntOption()

    override def reset(): Unit = {
      id.setNull()
      n.setNull()
    }
    override def copyFrom(other: Baz): Unit = {
      id.copyFrom(other.id)
      n.copyFrom(other.n)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      n.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      n.write(out)
    }

    def getIdOption: IntOption = id
    def getNOption: IntOption = n
  }

  object Baz {

    def intToBaz: Int => Baz = {

      lazy val baz = new Baz()

      { i =>
        baz.id.modify(i % 2)
        baz.n.modify(100 * i)
        baz
      }
    }

    def round(baz: Baz, round: Int): Baz = {
      baz.id.modify(100 * round + baz.id.get)
      baz.n.modify(100 * round + baz.n.get)
      baz
    }
  }

  @Summarized(term = new Summarized.Term(
    source = classOf[Baz],
    shuffle = new Key(group = Array("id")),
    foldings = Array(
      new Summarized.Folding(source = "id", destination = "id", aggregator = Summarized.Aggregator.ANY),
      new Summarized.Folding(source = "n", destination = "sum", aggregator = Summarized.Aggregator.SUM),
      new Summarized.Folding(source = "n", destination = "max", aggregator = Summarized.Aggregator.MAX),
      new Summarized.Folding(source = "n", destination = "min", aggregator = Summarized.Aggregator.MIN),
      new Summarized.Folding(source = "id", destination = "count", aggregator = Summarized.Aggregator.COUNT))))
  class SummarizedBaz extends DataModel[SummarizedBaz] with Writable {

    val id = new IntOption()
    val sum = new LongOption()
    val max = new IntOption()
    val min = new IntOption()
    val count = new LongOption()

    override def reset(): Unit = {
      id.setNull()
      sum.setNull()
      max.setNull()
      min.setNull()
      count.setNull()
    }
    override def copyFrom(other: SummarizedBaz): Unit = {
      id.copyFrom(other.id)
      sum.copyFrom(other.sum)
      max.copyFrom(other.max)
      min.copyFrom(other.min)
      count.copyFrom(other.count)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
      sum.readFields(in)
      max.readFields(in)
      min.readFields(in)
      count.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
      sum.write(out)
      max.write(out)
      min.write(out)
      count.write(out)
    }

    def getIdOption: IntOption = id
    def getSumOption: LongOption = sum
    def getMaxOption: IntOption = max
    def getMinOption: IntOption = min
    def getCountOption: LongOption = count
  }

  class Ops {

    @Update
    def roundFoo(foo: Foo): Unit = {
      val round = BatchContext.get("round").toInt
      Foo.round(foo, round)
    }

    @Update
    def roundBar(bar: Bar): Unit = {
      val round = BatchContext.get("round").toInt
      Bar.round(bar, round)
    }

    @Update
    def roundBaz(baz: Baz): Unit = {
      val round = BatchContext.get("round").toInt
      Baz.round(baz, round)
    }

    @Logging(Logging.Level.INFO)
    def logging(foo: Foo): String = {
      s"Foo(${foo.id},${foo.foo})"
    }

    @Extract
    def extract(foo: Foo, evenResult: Result[Foo], oddResult: Result[Foo]): Unit = {
      if (foo.id.get % 2 == 0) {
        evenResult.add(foo)
      } else {
        oddResult.add(foo)
      }
    }

    @CoGroup
    def cogroup(
      foos: JList[Foo], bars: JList[Bar],
      fooResult: Result[Foo], barResult: Result[Bar],
      fooError: Result[Foo], barError: Result[Bar]): Unit = {
      if (foos.size == 1 && bars.size == 1) {
        fooResult.add(foos(0))
        barResult.add(bars(0))
      } else {
        foos.foreach(fooError.add)
        bars.foreach(barError.add)
      }
    }

    @MasterCheck
    def mastercheck(foo: Foo, bar: Bar): Boolean = ???

    @MasterJoin
    def masterjoin(foo: Foo, bar: Bar): FooBar = ???

    @Fold(partialAggregation = PartialAggregation.PARTIAL)
    def fold(acc: Baz, each: Baz): Unit = {
      acc.n.add(each.n)
    }

    @Summarize
    def summarize(value: Baz): SummarizedBaz = ???
  }

  class FooSequenceFileFormat extends SequenceFileFormat[NullWritable, Foo, Foo] {

    override def getSupportedType(): Class[Foo] = classOf[Foo]

    override def createKeyObject(): NullWritable = NullWritable.get()

    override def createValueObject(): Foo = new Foo()

    override def copyToModel(key: NullWritable, value: Foo, model: Foo): Unit = {
      model.copyFrom(value)
    }

    override def copyFromModel(model: Foo, key: NullWritable, value: Foo): Unit = {
      value.copyFrom(model)
    }
  }

  class BazSequenceFileFormat extends SequenceFileFormat[NullWritable, Baz, Baz] {

    override def getSupportedType(): Class[Baz] = classOf[Baz]

    override def createKeyObject(): NullWritable = NullWritable.get()

    override def createValueObject(): Baz = new Baz()

    override def copyToModel(key: NullWritable, value: Baz, model: Baz): Unit = {
      model.copyFrom(value)
    }

    override def copyFromModel(model: Baz, key: NullWritable, value: Baz): Unit = {
      value.copyFrom(model)
    }
  }
}
