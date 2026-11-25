package chipyard.generator

import sbt.{Resolver => _, _}
import sbt.Keys._

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
  }

  import autoImport._

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
    hardwareResolve := run(updateLock = true).value,
    hardwareVerify := run(updateLock = false).value
  )

  private def run(updateLock: Boolean): Def.Initialize[Task[File]] = Def.task {
    val workspace = baseDirectory.value.toPath
    val output = target.value.toPath
    val version = chipyardVersion.value
    val dependencies = hardwareDependencies.value
    val toolchain = hardwareToolchain.value
    val cache = new GitCache(hardwareCacheDirectory.value.toPath, hardwareOffline.value)
    val log = streams.value.log
    try {
      val platform = Option(version).filter(_.nonEmpty).map(ChipyardRecipes.forCommit)
      val resolution = new Resolver(cache).resolve(workspace, dependencies, platform, toolchain)
      val plan = ResolutionFiles.write(workspace, output, resolution, updateLock)
      log.info(s"Resolved ${resolution.nodes.size} hardware dependencies. Plan: $plan")
      plan.toFile
    } catch {
      case error: ResolutionException => throw new MessageOnlyException(error.getMessage)
    }
  }
}
