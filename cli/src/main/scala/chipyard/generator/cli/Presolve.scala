package chipyard.generator.cli

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
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
      |  build <experiment>          Build a Verilator simulator
      |  build-fpga <experiment>     FPGA bitstream build placeholder
      |  software setup              Initialize FireMarshal in the configured Chipyard checkout
      |  software shell              Open Bash with the workspace's Chipyard Conda environment
      |  run <experiment> <elf> [--build <build-dir>]
      |                              Run an ELF with an existing simulator (alias: simulate)
      |
      |Workspace commands look for build.sbt in the current directory and its parents.
      |Resolve, verify, compile, standalone, build, software, and run require sbt on PATH.
      |Install the matching plugin with the release installer or run sbt publishLocal from the tool checkout.
      |Resolve before compiling or running. Presolve looks for the pinned Chipyard
      |toolchain under $XDG_DATA_HOME/presolve (default: $HOME/.local/share/presolve).
      |Use CHIPYARD_TOOLCHAIN_PREFIX or hardwareChipyardToolchain in build.sbt to override it.
      |ELF and build paths are relative to the directory where you invoke presolve.
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
    case "software" :: "setup" :: Nil => sbt(workspaceRoot(cwd), "hardwareSoftwareSetup")
    case "software" :: "shell" :: Nil => softwareShell(workspaceRoot(cwd))
    case ("compile" | "elaborate") :: name :: Nil =>
      val workspace = workspaceRoot(cwd)
      checkExperiment(workspace, name)
      sbt(workspace, s"hardwareElaborate $name")
    case "build" :: name :: Nil =>
      val workspace = workspaceRoot(cwd)
      checkExperiment(workspace, name)
      sbt(workspace, s"hardwareBuildSimulator $name")
    case "build-fpga" :: name :: Nil =>
      checkExperiment(workspaceRoot(cwd), name)
      Console.err.println("presolve: FPGA bitstream build is not implemented yet.")
      1
    case ("run" | "simulate") :: name :: elf :: Nil => simulate(cwd, name, elf, None)
    case ("run" | "simulate") :: name :: elf :: "--build" :: build :: Nil =>
      simulate(cwd, name, elf, Some(cwd.resolve(build).normalize()))
    case _ =>
      Console.err.println("presolve: Unknown command or incorrect arguments. See presolve --help.")
      2
  }

  private def softwareShell(workspace: Path): Int = {
    val prefixFile = Files.createTempFile("presolve-conda-prefix-", ".txt")
    val prefix = try {
      val status = sbt(workspace, s"hardwareSoftwareShellPrefix ${quoteSbtArgument(prefixFile.toString)}",
        Seq("-batch", "-error"))
      if (status != 0) return status
      val path = new String(Files.readAllBytes(prefixFile), UTF_8)
      if (path.isEmpty) throw new IOException("sbt did not return a Chipyard Conda environment.")
      path
    } finally Files.deleteIfExists(prefixFile)

    val startup = Files.createTempFile("presolve-software-shell-", ".bash")
    try {
      val script = getClass.getResourceAsStream("/software-shell.bash")
      if (script == null) throw new IOException("The Presolve Bash startup script is missing.")
      try Files.copy(script, startup, StandardCopyOption.REPLACE_EXISTING)
      finally script.close()
      val bash = new ProcessBuilder("bash", "--rcfile", startup.toString, "-i")
        .directory(workspace.toFile).inheritIO()
      bash.environment().put("PRESOLVE_SOFTWARE_PREFIX", prefix)
      bash.start().waitFor()
    } finally Files.deleteIfExists(startup)
  }

  private def simulate(cwd: Path, name: String, elf: String, build: Option[Path]): Int = {
    val workspace = workspaceRoot(cwd)
    if (!name.matches(experimentName))
      throw new IllegalArgumentException(s"Invalid experiment name '$name'.")
    if (build.isEmpty) checkExperiment(workspace, name)
    val payload = cwd.resolve(elf).normalize()
    if (!Files.isRegularFile(payload))
      throw new IllegalArgumentException(s"Simulation payload does not exist: $payload")
    val selection = build.fold("")(path => s" --build ${quoteSbtArgument(path.toString)}")
    sbt(workspace, s"hardwareSimulate $name ${quoteSbtArgument(payload.toString)}$selection")
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

  private def sbt(workspace: Path, command: String, options: Seq[String] = Nil): Int = {
    val arguments = "sbt" +: options :+ command
    val process = try new ProcessBuilder(arguments.asJava).directory(workspace.toFile).inheritIO().start()
    catch {
      case error: IOException =>
        throw new IOException(s"Could not start sbt in $workspace. Check that sbt is installed and on PATH.", error)
    }
    process.waitFor()
  }
}
