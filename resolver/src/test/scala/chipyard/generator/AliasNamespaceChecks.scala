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

      println("Accepts an alias that is a valid Scala package identifier.")
      AliasNamespace.validateAlias("generator_baseline")
      println("Rejects hyphenated aliases as Scala package identifiers.")
      expectFailure("Scala package identifier") { AliasNamespace.validateAlias("generator-baseline") }
      println("Rejects Scala keywords as aliases.")
      expectFailure("Scala package identifier") { AliasNamespace.validateAlias("class") }
      println("Rejects the reserved Scala root package as an alias.")
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
      println("Identifies the generator's top-level package across its source files.")
      assert(AliasNamespace.root(Vector(implementation, packageObject, nested), "generator_baseline") == "generator")
      println("Relocates generator references while preserving shared packages, literals, and shadowed names.")
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
      println("Relocates a top-level package object and its generator references.")
      assert(relocate(packageObject) == "package object generator_baseline { val tool = generator_baseline.util.Tool() }\n")
      println("Preserves relative package names and visibility qualifiers in nested package blocks.")
      assert(relocate(nested) ==
        "package generator_baseline { package generator.detail { class Nested { private[generator] val id = 1 } } }\n")

      println("Rejects a generator with multiple top-level Scala packages.")
      expectFailure("one top-level Scala package") {
        AliasNamespace.root(Vector(implementation, source("Other.scala", "package other\nclass Else\n")), "ambiguous")
      }
      println("Rejects a generator declared in Scala's default package.")
      expectFailure("must declare a named package") {
        AliasNamespace.root(Vector(source("Default.scala", "object InDefaultPackage {}")), "default_alias")
      }
      println("Alias namespace checks passed.")
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
