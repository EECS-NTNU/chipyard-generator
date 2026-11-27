package chipyard.generator

import java.nio.file.{Files, Paths}
import java.util.Locale
import sbt.{Resolver => _, _}
import sbt.Keys._
import sbt.complete.DefaultParsers.spaceDelimited

object ChipyardPlugin extends AutoPlugin {
  object autoImport {
    val Generator = chipyard.generator.Generator
    val chipyardVersion = settingKey[String]("Exact Chipyard commit. Empty for a platform-independent workspace.")
    val hardwareDependencies = settingKey[Seq[chipyard.generator.Generator]]("Workspace hardware dependency roots.")
    val hardwareToolchain = settingKey[Map[String, String]]("Additional toolchain pins for the workspace.")
    val hardwareCacheDirectory = settingKey[File]("Shared cache for pinned hardware sources.")
    val hardwareOffline = settingKey[Boolean]("Resolve exclusively from prefetched sources.")
    val hardwareResolve = taskKey[File]("Resolve dependencies, update hardware.lock and write a build plan.")
    val hardwareVerify = taskKey[File]("Verify hardware.lock and write a build plan without modifying the lock.")
    val hardwareChipyardToolchain = settingKey[Option[File]]("Toolchain prefix for the pinned Chipyard build recipe.")
    val hardwareBuildJobs = settingKey[Int]("Parallel jobs for the generated Chipyard build.")
    val hardwareElaborate = inputKey[File]("Elaborate one experiment to Verilog. Usage: hardwareElaborate <experiment>")
    val hardwareElaborateStandalone = inputKey[File]("Elaborate the workspace Module without a system.")
    val hardwareBuildSimulator = inputKey[File]("Build a Verilator simulator. Usage: hardwareBuildSimulator <experiment>")
    val hardwareSimulate = inputKey[File]("Run an ELF. Usage: hardwareSimulate <experiment> <binary> [--build <build-dir>]")
    val hardwareSoftwareSetup = taskKey[File]("Initialize FireMarshal in the configured Chipyard checkout.")
  }

  import autoImport._

  private val hardwareResolution = taskKey[Resolution]("Evaluate the resolved hardware dependency graph.")
  private val hardwareSoftwareShellPrefix = inputKey[Unit]("Write the configured Conda prefix for the Presolve shell.")
  private sealed abstract class BuildCommand(val name: String, val arguments: List[String])
  private case object Elaborate extends BuildCommand("hardwareElaborate", List("<experiment>"))
  private case object BuildSimulator extends BuildCommand("hardwareBuildSimulator", List("<experiment>"))
  private case object Standalone extends BuildCommand("hardwareElaborateStandalone", Nil)

  override def trigger = noTrigger

  override lazy val projectSettings = Seq(
    chipyardVersion := "",
    hardwareDependencies := Seq.empty,
    hardwareToolchain := Map.empty,
    hardwareCacheDirectory := {
      val cacheHome = sys.env.get("XDG_CACHE_HOME").filter(_.nonEmpty).map(file)
        .getOrElse(file(sys.props("user.home")) / ".cache")
      cacheHome / "hardware-generators"
    },
    hardwareOffline := offline.value,
    hardwareChipyardToolchain := {
      val revision = chipyardVersion.value
      sys.env.get("CHIPYARD_TOOLCHAIN_PREFIX").filter(_.nonEmpty).map(file)
        .orElse(installedToolchain(revision))
    },
    hardwareBuildJobs := math.min(12, java.lang.Runtime.getRuntime.availableProcessors()),
    hardwareResolution := {
      val workspace = baseDirectory.value.toPath
      val version = chipyardVersion.value
      val dependencies = hardwareDependencies.value
      val toolchain = hardwareToolchain.value
      val cache = new GitCache(hardwareCacheDirectory.value.toPath, hardwareOffline.value)
      reportErrors {
        val platform = Option(version).filter(_.nonEmpty).map(ChipyardRecipes.forCommit)
        new Resolver(cache).resolve(workspace, dependencies, platform, toolchain)
      }
    },
    hardwareResolve := writeResolution(updateLock = true).value,
    hardwareVerify := writeResolution(updateLock = false).value,
    hardwareElaborate := build(Elaborate).evaluated,
    hardwareBuildSimulator := build(BuildSimulator).evaluated,
    hardwareSimulate := simulate.evaluated,
    hardwareElaborateStandalone := build(Standalone).evaluated,
    hardwareSoftwareShellPrefix := {
      val args = spaceDelimited("<output-file>").parsed
      val toolchain = hardwareChipyardToolchain.value
      if (args.size != 1)
        throw new MessageOnlyException("Usage: hardwareSoftwareShellPrefix <output-file>")
      val prefix = toolchain.getOrElse(throw new MessageOnlyException(
        "Set CHIPYARD_TOOLCHAIN_PREFIX or hardwareChipyardToolchain to the Chipyard toolchain prefix."))
        .getAbsoluteFile.toPath.normalize()
      if (!Files.isDirectory(prefix.resolve("conda-meta")))
        throw new MessageOnlyException(s"The configured Chipyard toolchain is not a Conda environment: $prefix")
      IO.write(file(args.head), prefix.toString)
    },
    hardwareSoftwareSetup := {
      val revision = chipyardVersion.value
      val toolchain = hardwareChipyardToolchain.value
      val offlineSetup = hardwareOffline.value
      val log = streams.value.log
      reportErrors {
        val prefix = toolchain.getOrElse(throw new MessageOnlyException(
          "Set CHIPYARD_TOOLCHAIN_PREFIX or hardwareChipyardToolchain to the Chipyard toolchain prefix."))
        ChipyardSoftware.setup(prefix.toPath, revision, offlineSetup, message => log.info(message),
          message => log.warn(message)).toFile
      }
    }
  )

  private def installedToolchain(revision: String): Option[File] =
    Option(revision).filter(_.matches("[0-9a-fA-F]{40}")).flatMap { commit =>
      val dataHome = sys.env.get("XDG_DATA_HOME").filter(_.nonEmpty).map(file)
        .getOrElse(file(sys.env.getOrElse("HOME", sys.props("user.home"))) / ".local" / "share")
      val prefix = dataHome / "presolve" / s"chipyard-${commit.toLowerCase(Locale.ROOT)}" / ".conda-env"
      val root = prefix.toPath
      val executables = Seq("bin/java", "bin/make", "bin/g++", "bin/verilator", "lib/jvm/bin/java",
        "riscv-tools/bin/firtool")
      val libraries = Seq("riscv-tools/lib/libfesvr.a", "riscv-tools/lib/libfesvr.so")
      val hasExecutables = executables.forall { name =>
        val executable = root.resolve(name)
        Files.isRegularFile(executable) && Files.isExecutable(executable)
      }
      val hasLibrary = libraries.exists(name => Files.isRegularFile(root.resolve(name)))
      if (hasExecutables && hasLibrary) Some(prefix) else None
    }

  private def writeResolution(updateLock: Boolean): Def.Initialize[Task[File]] = Def.task {
    val workspace = baseDirectory.value.toPath
    val output = target.value.toPath
    val resolution = hardwareResolution.value
    val log = streams.value.log
    reportErrors {
      val plan = ResolutionFiles.write(workspace, output, resolution, updateLock)
      log.info(s"Resolved ${resolution.nodes.size} hardware dependencies. Plan: $plan")
      plan.toFile
    }
  }

  private def build(command: BuildCommand): Def.Initialize[InputTask[File]] = Def.inputTask {
    val args = spaceDelimited("<argument>").parsed
    val workspace = baseDirectory.value.toPath
    val output = target.value.toPath
    val cache = hardwareCacheDirectory.value.toPath
    val toolchain = hardwareChipyardToolchain.value
    val jobs = hardwareBuildJobs.value
    val resolution = hardwareResolution.value
    val log = streams.value.log
    if (args.size != command.arguments.size)
      throw new MessageOnlyException("Usage: " + (command.name :: command.arguments).mkString(" "))
    if (jobs < 1) throw new MessageOnlyException("hardwareBuildJobs must be positive.")

    reportErrors {
      ResolutionFiles.write(workspace, output, resolution, updateLock = false)
      val prefix = toolchain.getOrElse(throw new MessageOnlyException(
        "Set CHIPYARD_TOOLCHAIN_PREFIX or hardwareChipyardToolchain to the Chipyard toolchain prefix."))
      ChipyardBuild.build(workspace, output, cache, resolution, args.headOption, prefix.toPath, jobs,
        command == BuildSimulator, line => log.info(line), line => log.warn(line)).toFile
    }
  }

  private def simulate: Def.Initialize[InputTask[File]] = Def.inputTaskDyn {
    spaceDelimited("<argument>").parsed.toList match {
      case experiment :: payload :: Nil => Def.task {
        val workspace = baseDirectory.value.toPath
        val output = target.value.toPath
        val resolution = hardwareResolution.value
        val toolchain = hardwareChipyardToolchain.value
        val log = streams.value.log
        reportErrors {
          ResolutionFiles.write(workspace, output, resolution, updateLock = false)
          val prefix = toolchain.getOrElse(throw new MessageOnlyException(
            "Set CHIPYARD_TOOLCHAIN_PREFIX or hardwareChipyardToolchain to the Chipyard toolchain prefix."))
          val build = ChipyardBuild.findSimulator(workspace, output, resolution, experiment, prefix.toPath)
          ChipyardBuild.simulate(workspace, output, experiment, Paths.get(payload), build,
            line => log.info(line), line => log.warn(line)).toFile
        }
      }
      case experiment :: payload :: "--build" :: build :: Nil => Def.task {
        val workspace = baseDirectory.value.toPath
        val output = target.value.toPath
        val log = streams.value.log
        reportErrors {
          ChipyardBuild.simulate(workspace, output, experiment, Paths.get(payload), Paths.get(build),
            line => log.info(line), line => log.warn(line)).toFile
        }
      }
      case _ => throw new MessageOnlyException(
        "Usage: hardwareSimulate <experiment> <binary> [--build <build-dir>]")
    }
  }

  private def reportErrors[A](operation: => A): A =
    try operation
    catch {
      case error: ResolutionException => throw new MessageOnlyException(error.getMessage)
    }
}
