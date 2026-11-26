package chipyard.generator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

object AliasNamespaceChecks {
  def main(args: Array[String]): Unit = {
    val work = Files.createTempDirectory("alias-namespace-")
    try {
      def source(file: String, text: String): SourceSelection.ScalaSource = {
        val path = work.resolve(file)
        Files.write(path, text.getBytes(UTF_8))
        SourceSelection.ScalaSource(path, file)
      }

      def relocate(source: SourceSelection.ScalaSource): String = {
        AliasNamespace.relocate(source, "generator", "generator_baseline")
        new String(Files.readAllBytes(source.path), UTF_8)
      }

      AliasNamespace.validateAlias("generator_baseline")
      expectFailure("Scala package identifier") { AliasNamespace.validateAlias("generator-baseline") }
      expectFailure("Scala package identifier") { AliasNamespace.validateAlias("class") }
      expectFailure("Scala package identifier") { AliasNamespace.validateAlias("_root_") }

      val implementation = source("Core.scala",
        """package generator.core
          |
          |import _root_.generator.util.{Tool => LocalTool, _}
          |import generator.util.Helper
          |import _root_.shared.generator.Value
          |
          |private[generator] class Node extends generator.Base {
          |  protected[generator] def make: _root_.generator.util.Tool = generator.util.Tool()
          |  val unrelated = shared.generator.Tool
          |  val text = "generator.util.Tool" // generator.util.Tool
          |  val interpolated = s"${generator.util.value}"
          |  def withParameter(generator: String) = generator.length
          |  def withLocal = { val generator = ""; generator.length }
          |  def withImport = { import external.{something => generator}; generator.call() }
          |  def withPattern(value: Any) = value match {
          |    case generator: String => generator.length
          |    case _ => generator.util.default
          |  }
          |}
          |""".stripMargin)
      val packageObject = source("package.scala", "package object generator { val tool = generator.util.Tool() }\n")
      val nested = source("Nested.scala",
        "package generator { package generator.detail { class Nested { private[generator] val id = 1 } } }\n")
      assert(AliasNamespace.root(Vector(implementation, packageObject, nested), "generator_baseline") == "generator")
      assert(relocate(implementation) ==
        """package generator_baseline.core
          |
          |import _root_.generator_baseline.util.{Tool => LocalTool, _}
          |import generator_baseline.util.Helper
          |import _root_.shared.generator.Value
          |
          |private[generator_baseline] class Node extends generator_baseline.Base {
          |  protected[generator_baseline] def make: _root_.generator_baseline.util.Tool = generator_baseline.util.Tool()
          |  val unrelated = shared.generator.Tool
          |  val text = "generator.util.Tool" // generator.util.Tool
          |  val interpolated = s"${generator_baseline.util.value}"
          |  def withParameter(generator: String) = generator.length
          |  def withLocal = { val generator = ""; generator.length }
          |  def withImport = { import external.{something => generator}; generator.call() }
          |  def withPattern(value: Any) = value match {
          |    case generator: String => generator.length
          |    case _ => generator_baseline.util.default
          |  }
          |}
          |""".stripMargin)
      assert(relocate(packageObject) == "package object generator_baseline { val tool = generator_baseline.util.Tool() }\n")
      assert(relocate(nested) ==
        "package generator_baseline { package generator.detail { class Nested { private[generator] val id = 1 } } }\n")

      expectFailure("one top-level Scala package") {
        AliasNamespace.root(Vector(implementation, source("Other.scala", "package other\nclass Else\n")), "ambiguous")
      }
      expectFailure("must declare a named package") {
        AliasNamespace.root(Vector(source("Default.scala", "object InDefaultPackage {}")), "default_alias")
      }
      println("Milestone 4 alias namespace checks passed.")
    } finally FilesSupport.deleteTree(work)
  }

  private def expectFailure(message: String)(operation: => Unit): Unit = {
    val failure = try {
      operation
      throw new AssertionError(s"Expected a resolution error containing '$message'.")
    } catch {
      case error: ResolutionException => error
    }
    assert(failure.getMessage.contains(message), failure.getMessage)
  }
}
