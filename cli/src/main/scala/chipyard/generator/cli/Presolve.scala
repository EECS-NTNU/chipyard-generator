package chipyard.generator.cli

import java.io.IOException
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

object Presolve {
  private val usage =
    """Usage: presolve <command> [arguments]
      |
      |Commands
      |  create-project <directory>   Create a workspace with a Rocket baseline
      |  experiments                  List available experiment names
      |  resolve                      Create or update hardware.lock
      |  verify                       Check hardware.lock
      |  compile <experiment>        Elaborate an experiment to RTL (alias: elaborate)
      |  standalone                  Elaborate the workspace Module to RTL
      |  run <experiment> <elf>       Build a Verilator simulator and run an ELF (alias: simulate)
      |
      |Workspace commands look for build.sbt in the current directory and its parents.
      |Resolve, verify, compile, standalone, and run require sbt on PATH. For a generated workspace,
      |publish the plugin locally with sbt publishLocal from the tool checkout.
      |Resolve before compiling or running. Set CHIPYARD_TOOLCHAIN_PREFIX for the
      |pinned Chipyard toolchain, or configure hardwareChipyardToolchain in build.sbt.
      |ELF paths are relative to the directory where you invoke presolve.
      |""".stripMargin

  private val experimentName = "[A-Za-z0-9][A-Za-z0-9_-]*"

  def main(args: Array[String]): Unit = {
    val status = try run(args.toList, Paths.get("").toAbsolutePath.normalize())
    catch {
      case error: IllegalArgumentException =>
        Console.err.println(s"presolve: ${error.getMessage}")
        2
      case error: IOException =>
        Console.err.println(s"presolve: ${error.getMessage}")
        1
    }
    if (status != 0) sys.exit(status)
  }

  private def run(args: List[String], cwd: Path): Int = args match {
    case Nil | ("-h" | "--help" | "help") :: Nil =>
      print(usage)
      0
    case "create-project" :: directory :: Nil =>
      val workspace = cwd.resolve(directory).normalize()
      WorkspaceTemplate.create(workspace)
      println(s"Created workspace at $workspace")
      println("Run presolve resolve from the workspace to generate hardware.lock.")
      0
    case "experiments" :: Nil =>
      val config = workspaceRoot(cwd).resolve("config")
      if (Files.isDirectory(config)) {
        val entries = Files.list(config)
        try {
          entries.iterator().asScala
            .filter(entry => Files.isRegularFile(entry.resolve("System.scala")))
            .map(_.getFileName.toString)
            .filter(_.matches(experimentName))
            .toVector.sorted.foreach(println)
        } finally entries.close()
      }
      0
    case "resolve" :: Nil => sbt(workspaceRoot(cwd), "hardwareResolve")
    case "verify" :: Nil => sbt(workspaceRoot(cwd), "hardwareVerify")
    case "standalone" :: Nil => sbt(workspaceRoot(cwd), "hardwareElaborateStandalone")
    case ("compile" | "elaborate") :: name :: Nil =>
      val workspace = workspaceRoot(cwd)
      checkExperiment(workspace, name)
      sbt(workspace, s"hardwareElaborate $name")
    case ("run" | "simulate") :: name :: elf :: Nil =>
      val workspace = workspaceRoot(cwd)
      checkExperiment(workspace, name)
      val payload = cwd.resolve(elf).normalize()
      if (!Files.isRegularFile(payload))
        throw new IllegalArgumentException(s"Simulation payload does not exist: $payload")
      sbt(workspace, s"hardwareSimulate $name ${quoteSbtArgument(payload.toString)}")
    case _ =>
      Console.err.println("presolve: Unknown command or incorrect arguments. See presolve --help.")
      2
  }

  private def workspaceRoot(cwd: Path): Path =
    Iterator.iterate(cwd)(_.getParent).takeWhile(_ != null)
      .find(directory => Files.isRegularFile(directory.resolve("build.sbt")))
      .getOrElse(throw new IllegalArgumentException(s"No build.sbt found at or above $cwd."))

  private def checkExperiment(workspace: Path, name: String): Unit = {
    if (!name.matches(experimentName))
      throw new IllegalArgumentException(s"Invalid experiment name '$name'.")
    val entry = workspace.resolve(s"config/$name/System.scala")
    if (!Files.isRegularFile(entry))
      throw new IllegalArgumentException(s"Missing experiment configuration: $entry")
  }

  private def quoteSbtArgument(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def sbt(workspace: Path, command: String): Int = {
    val process = try new ProcessBuilder("sbt", command).directory(workspace.toFile).inheritIO().start()
    catch {
      case error: IOException =>
        throw new IOException(s"Could not start sbt in $workspace. Check that sbt is installed and on PATH.", error)
    }
    process.waitFor()
  }
}
