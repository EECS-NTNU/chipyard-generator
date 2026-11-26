package chipyard.generator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

object SourceSelectionChecks {
  def main(args: Array[String]): Unit = {
    val temp = Files.createTempDirectory("hardware-source-selection-")
    try check(temp)
    finally FilesSupport.deleteTree(temp)
  }

  private def check(temp: Path): Unit = {
    val workspace = project(temp, "workspace", "example")
    val dependency = project(temp, "dependency", "dependency")
    unit(workspace, "active", "package example.integrations.active\n" +
      "import _root_.dependency.integrations.leaf.{A, B => RenamedB}\nobject Selected")
    unit(workspace, "broken", "package example.integrations.broken\nobject Broken {")
    unit(dependency, "leaf", "package dependency.integrations.leaf\nobject A\nobject B")
    val nested = project(temp, "nested", "example.integrations.active.nested")
    unit(nested, "inner", "package example.integrations.active.nested.integrations.inner\nobject More")

    val helper = write(workspace.staged.resolve("src/main/scala/Helper.scala"),
      """package example
        |/* import example.integrations.broken.Broken */
        |object Helper { val text = "import example.integrations.broken.Broken" }
        |""".stripMargin)
    val config = write(workspace.staged.resolve("config/System.scala"),
      """// Header
        |package example
        |
        |import example.integrations.active.Selected
        |class System
        |""".stripMargin)

    def plan(extra: String, owners: Vector[SourceSelection.Project] = Vector(workspace, dependency, nested)) = {
      val entry = write(config.resolveSibling("Probe.scala"), extra)
      SourceSelection.plan(owners, Vector(SourceSelection.ScalaSource(entry, "config/Probe.scala")), _ => ())
    }

    val selected = plan(new String(Files.readAllBytes(config), UTF_8))
    val reasons = selected.units.collect { case (source, Some(reason)) => source.packageName -> reason }.toMap
    assert(reasons.keySet == Set("example.integrations.active", "dependency.integrations.leaf"))
    assert(reasons("example.integrations.active").line == 4)
    assert(reasons("dependency.integrations.leaf").source.endsWith("Unit.scala"))
    assert(selected.sources.exists(_.endsWith(helper.getFileName.toString)))
    assert(selected.units.exists { case (source, reason) => source.name == "broken" && reason.isEmpty })

    assert(SourceSelection.entryPackage(workspace.source(config), "System", module = false) == "example")
    assert(SourceSelection.entryPackage(SourceSelection.moduleEntry(workspace), "Module", module = true) == "example")
    assert(plan("package example\nimport example.integrations.active")
      .selected.map(_.name).toSet == Set("active", "leaf"))
    assert(plan("package example\nimport example.integrations.active.nested.integrations.inner.More")
      .selected.map(_.name) == Vector("inner"))

    expect("cannot select an optional integration") {
      plan("package example\nimport example.integrations.broken._")
    }
    expect("integrations/broken") {
      plan("package example\nimport example.integrations.broken.Broken")
    }
    val duplicate = project(temp, "duplicate", "example")
    unit(duplicate, "active", "package example.integrations.active\nobject Duplicate")
    expect("is ambiguous") {
      plan("package example", Vector(workspace, duplicate))
    }
    checkAliasActivation(temp, workspace, config)
    println("Milestone 3 source selection checks passed.")
  }

  private def checkAliasActivation(temp: Path, workspace: SourceSelection.Project, config: Path): Unit = {
    val counter = project(temp, "counter", "counter_baseline")
    val filter = project(temp, "filter", "filter_experimental")
    val inactive = project(temp, "unused", "unused")
    unit(counter, "adapter", """package counter_baseline.integrations.adapter
      |import _root_.filter_experimental.integrations.tap.{Signal => TapSignal}
      |object Counter
      |""".stripMargin)
    unit(filter, "tap", "package filter_experimental.integrations.tap\nobject Signal")
    write(inactive.staged.resolve("src/main/scala/Broken.scala"), "object Broken {")
    val aliases = Map("counter_baseline" -> counter, "filter_experimental" -> filter, "unused" -> inactive)

    def plan(imports: String) = {
      val entry = write(config.resolveSibling("AliasProbe.scala"), s"package example\n$imports\nclass System")
      SourceSelection.plan(
        Vector(workspace),
        Vector(SourceSelection.ScalaSource(entry, "config/AliasProbe.scala")),
        _ => (),
        (imported, _) => aliases.get(imported.takeWhile(_ != '.')).toVector,
        aliases.map { case (namespace, project) => namespace -> project.id })
    }

    val selected = plan("""import counter_baseline.integrations.adapter.Counter
      |import counter_baseline.integrations.adapter._
      |/* import unused.Module */
      |""".stripMargin)
    assert(selected.selected.map(_.packageName) ==
      Vector("counter_baseline.integrations.adapter", "filter_experimental.integrations.tap"))
    assert(selected.sources.contains("counter:src/main/scala/Module.scala"))
    assert(selected.sources.contains("filter:src/main/scala/Module.scala"))
    assert(!selected.sources.exists(_.startsWith("unused:")))
    expect("cannot select an aliased generator") {
      plan("import unused._")
    }
  }

  private def project(temp: Path, id: String, namespace: String): SourceSelection.Project = {
    val root = temp.resolve(id)
    write(root.resolve("src/main/scala/Module.scala"), s"package $namespace\nobject Module")
    SourceSelection.Project(id, root, root)
  }

  private def unit(project: SourceSelection.Project, name: String, source: String): Unit =
    write(project.original.resolve(s"integrations/$name/src/main/scala/Unit.scala"), source)

  private def write(path: Path, source: String): Path = {
    Files.createDirectories(path.getParent)
    Files.write(path, source.getBytes(UTF_8))
  }

  private def expect(message: String)(action: => Any): Unit = {
    try {
      action
      throw new AssertionError(s"Expected a source selection failure containing '$message'.")
    } catch {
      case error: ResolutionException => assert(error.getMessage.contains(message), error.getMessage)
    }
  }
}
