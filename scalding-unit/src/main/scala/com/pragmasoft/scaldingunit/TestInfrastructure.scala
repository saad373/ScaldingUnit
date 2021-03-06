package com.pragmasoft.scaldingunit

import com.twitter.scalding._
import scala.collection.mutable.Buffer
import cascading.tuple.Fields
import scala.Predef._
import com.twitter.scalding.Tsv
import org.slf4j.LoggerFactory
import scala.language.implicitConversions


trait TestInfrastructure extends FieldConversions with TupleConversions with PipeOperations with PipeOperationsConversions {

  val log = LoggerFactory.getLogger(this.getClass.getName)

  def given(source: TestSource): TestCaseGiven1 = new TestCaseGiven1(source)

  def given(sources: List[TestSource]): TestCaseGivenList = new TestCaseGivenList(sources)

  trait TestSourceWithoutSchema {
    def addSourceToJob(jobTest: JobTest, source: Source): JobTest

    def withSchema(schema: Fields) = new TestSource(this, schema)
  }


  class ProductTestSourceWithoutSchema(val data: Iterable[Product]) extends TestSourceWithoutSchema {
    def addSourceToJob(jobTest: JobTest, source: Source): JobTest = jobTest.source(source, data)
  }

  class SimpleTypeTestSourceWithoutSchema[T](val data: Iterable[T])(implicit setter: TupleSetter[T]) extends TestSourceWithoutSchema {
    println("Setter " + setter.getClass.getName)

    def addSourceToJob(jobTest: JobTest, source: Source): JobTest =
      jobTest.source[T](source, data)(setter)
  }

  implicit def fromProductDataToSourceWithoutSchema(data: Iterable[Product]) = new ProductTestSourceWithoutSchema(data)

  implicit def fromSimpleTypeDataToSourceWithoutSchema[T](data: Iterable[T])(implicit setter: TupleSetter[T]) =
    new SimpleTypeTestSourceWithoutSchema(data)(setter)

  class TestSource(data: TestSourceWithoutSchema, schema: Fields) {
    def sources = List(this)

    def name: String = "Source_" + hashCode

    def asSource: Source = Tsv(name, schema)

    def addSourceDataToJobTest(jobTest: JobTest) = data.addSourceToJob(jobTest, asSource)
  }

  case class TestCaseGiven1(source: TestSource) {
    def and(other: TestSource) = TestCaseGiven2(source, other)

    def when(op: OnePipeOperation): TestCaseWhen = TestCaseWhen(List(source), op)
  }

  case class TestCaseGiven2(source: TestSource, other: TestSource) {
    def and(third: TestSource) = TestCaseGiven3(source, other, third)

    def when(op: TwoPipesOperation): TestCaseWhen = TestCaseWhen(List(source, other), op)
  }

  case class TestCaseGiven3(source: TestSource, other: TestSource, third: TestSource) {
    def and(next: TestSource) = TestCaseGivenList(List(source, other, third, next))

    def when(op: ThreePipesOperation): TestCaseWhen = TestCaseWhen(List(source, other, third), op)
  }

  case class TestCaseGivenList(sources: List[TestSource]) {
    def and(next: TestSource) = TestCaseGivenList((next :: sources.reverse).reverse)

    def when(op: PipeOperation): TestCaseWhen = TestCaseWhen(sources, op)
  }

  case class TestCaseWhen(sources: List[TestSource], operation: PipeOperation) {
    def ensure[OutputType](assertion: Buffer[OutputType] => Unit)(implicit conv: TupleConverter[OutputType]) = {
      CompleteTestCase(sources, operation, assertion)
    }
  }

  case class CompleteTestCase[OutputType](sources: List[TestSource], operation: PipeOperation, assertion: Buffer[OutputType] => Unit)(implicit conv: TupleConverter[OutputType]) {

    class DummyJob(args: Args) extends Job(args) {
      val inputPipes: List[RichPipe] = sources.map(testSource => RichPipe(testSource.asSource.read))

      val outputPipe = operation(inputPipes)

      outputPipe.debug.write(Tsv("output"))
    }

    val jobTest = JobTest(new DummyJob(_))

    // Add Sources
    val op = sources.foreach {
      _.addSourceDataToJobTest(jobTest)
    }
    // Add Sink
    jobTest.sink[OutputType](Tsv("output")) {
      assertion(_)
    }

    // Execute
    jobTest.run.finish
  }

}
