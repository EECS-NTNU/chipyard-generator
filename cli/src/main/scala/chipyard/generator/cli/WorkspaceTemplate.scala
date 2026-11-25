package chipyard.generator.cli

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileAlreadyExistsException, Files, Path}
import java.util.{Locale, Properties}
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.control.NonFatal

private object WorkspaceTemplate {
  private lazy val chipyardCommit = {
    val revision = Source.fromResource("chipyard-revision")
    try revision.mkString.trim
    finally revision.close()
  }

  private lazy val buildMetadata = {
    val properties = new Properties()
    val stream = getClass.getResourceAsStream("/presolve.properties")
    try properties.load(stream)
    finally stream.close()
    properties
  }

  /** Creates a workspace in a new directory and removes its generated files if scaffolding fails. */
  def create(workspace: Path): Unit = {
    val name = Option(workspace.getFileName).map(_.toString).getOrElse("")
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9_-]*"))
      throw new IllegalArgumentException("Use a project directory name starting with a letter or digit and containing only letters, digits, hyphens or underscores.")

    val packageName = "workspace_" + name.toLowerCase(Locale.ROOT).replace('-', '_')
    val organization = buildMetadata.getProperty("organization")
    val plugin = buildMetadata.getProperty("plugin")
    val version = buildMetadata.getProperty("version")
    val sbtVersion = buildMetadata.getProperty("sbt")
    val directories = Vector(
      "project", "src", "src/main", "src/main/scala", s"src/main/scala/$packageName",
      "src/test", "src/test/scala", s"src/test/scala/$packageName",
      "integrations", "config", "config/baseline"
    )
    val files = Vector[(String, String)](
      "build.sbt" ->
        s"""enablePlugins(ChipyardPlugin)
           |
           |name := "$name"
           |version := "0.1.0"
           |chipyardVersion := "$chipyardCommit"
           |hardwareDependencies := Seq.empty
           |""".stripMargin,
      "project/build.properties" -> s"sbt.version=$sbtVersion\n",
      "project/plugins.sbt" -> (s"""addSbtPlugin("$organization" % "$plugin" % "$version")""" + "\n"),
      s"src/main/scala/$packageName/Module.scala" ->
        s"""package $packageName
           |
           |import chisel3._
           |
           |class Module extends chisel3.Module {
           |  val io = IO(new Bundle {
           |    val in = Input(UInt(8.W))
           |    val out = Output(UInt(8.W))
           |  })
           |
           |  io.out := RegNext(io.in, 0.U)
           |}
           |""".stripMargin,
      "config/baseline/System.scala" ->
        s"""package $packageName
           |
           |class System extends chipyard.RocketConfig
           |""".stripMargin,
      ".gitignore" -> "/target/\n/project/target/\n/project/project/\n/.bsp/\n/.bloop/\n/.metals/\n",
      "README.md" ->
        s"""# $name
           |
           |Install presolve and publish the Chipyard Generator plugin locally from the tool checkout.
           |Set `CHIPYARD_TOOLCHAIN_PREFIX` to the pinned Chipyard toolchain prefix before compiling or simulating.
           |
           |```sh
           |presolve experiments
           |presolve resolve
           |presolve verify
           |presolve compile baseline
           |presolve run baseline ./program.elf
           |```
           |
           |Use a RISC-V ELF for simulation. Commit the `hardware.lock` produced by `presolve resolve`.
           |Add project-wide dependencies in `build.sbt` and add experiments under `config/<name>/System.scala`.
           |The baseline elaborates stock Rocket. The starter `Module.scala` is available for custom experiments.
           |""".stripMargin
    )

    try Files.createDirectory(workspace)
    catch {
      case _: FileAlreadyExistsException => throw new IOException(s"Project directory already exists: $workspace")
    }
    val created = ArrayBuffer(workspace)
    try {
      directories.foreach(relative => created += Files.createDirectory(workspace.resolve(relative)))
      files.foreach { case (relative, content) =>
        val path = Files.createFile(workspace.resolve(relative))
        created += path
        Files.write(path, content.getBytes(UTF_8))
      }
    } catch {
      case NonFatal(error) =>
        created.reverseIterator.foreach { path =>
          try Files.deleteIfExists(path)
          catch { case NonFatal(cleanupError) => error.addSuppressed(cleanupError) }
        }
        throw error
    }
  }
}
