package chipyard.generator

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
    val hardwareSimulate = inputKey[File]("Run an ELF with Verilator. Usage: hardwareSimulate <experiment> <binary>")
  }

  import autoImport._

  private val hardwareResolution = taskKey[Resolution]("Evaluate the resolved hardware dependency graph.")
  private sealed abstract class BuildCommand(val name: String, val arguments: List[String])
  private case object Elaborate extends BuildCommand("hardwareElaborate", List("<experiment>"))
  private case object Simulate extends BuildCommand("hardwareSimulate", List("<experiment>", "<binary>"))
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
    hardwareChipyardToolchain := sys.env.get("CHIPYARD_TOOLCHAIN_PREFIX").filter(_.nonEmpty).map(file),
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
    hardwareSimulate := build(Simulate).evaluated,
    hardwareElaborateStandalone := build(Standalone).evaluated
  )

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
      ChipyardBuild.run(workspace, output, cache, resolution, args.headOption, prefix.toPath, jobs,
        args.lift(1).map(java.nio.file.Paths.get(_)), line => log.info(line), line => log.warn(line)).toFile
    }
  }

  private def reportErrors[A](operation: => A): A =
    try operation
    catch {
      case error: ResolutionException => throw new MessageOnlyException(error.getMessage)
    }
}
