package chipyard.generator

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

object ChipyardBuild {
  private val experimentName = "[A-Za-z0-9][A-Za-z0-9_-]*"
  private val makePath = "[A-Za-z0-9_./+@-]+"
  private val mainSources = Paths.get("src/main")
  private val simulationBinary = "payload/program.elf"
  private val inheritedBuildOverrides = Vector("USE_CHISEL7", "SBT", "MAKEFILES", "CLASSPATH_CACHE",
    "GENERATOR_CLASSPATH", "TAPEOUT_CLASSPATH", "SUB_PROJECT", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
  private val standaloneMain = "chipyardgenerator.internal.Standalone"
  private val builtInProjects = Map(
    "chipyard" -> "chipyard", "boom" -> "boom", "rocket-chip" -> "rocketchip",
    "hardfloat" -> "hardfloat", "diplomacy" -> "diplomacy", "pmu" -> "pmu",
    "cde" -> "cde", "firesim" -> "midas_target_utils"
  )

  /** Copies locked sources into a writable build and delegates elaboration or simulation to Chipyard Make. */
  def run(
      workspace: Path,
      target: Path,
      cacheDirectory: Path,
      resolution: Resolution,
      experiment: Option[String],
      toolchain: Path,
      jobs: Int,
      payload: Option[Path],
      info: String => Unit,
      warn: String => Unit
  ): Path = {
    val platform = resolution.platform.getOrElse(
      throw new ResolutionException("Set chipyardVersion before elaborating hardware."))
    val entry = experiment.map { name =>
      if (!name.matches(experimentName))
        throw new ResolutionException(s"Invalid experiment name '$name'.")
      val path = workspace.resolve(s"config/$name/System.scala")
      if (!Files.isRegularFile(path))
        throw new ResolutionException(s"Missing experiment configuration: $path")
      path
    }
    val standalone = experiment.isEmpty
    val buildName = experiment.getOrElse("standalone")
    val entryFile = if (standalone) "Standalone.scala" else "System.scala"
    val nodes = resolution.nodes.sortBy(_.id)
    nodes.groupBy(_.name).toVector.sortBy(_._1).collectFirst {
      case (name, instances) if instances.size > 1 => name
    }.foreach { name =>
      throw new ResolutionException(s"Building multiple instances of '$name' requires alias namespace support.")
    }
    val platformSources = nodes.find(_.id == "chipyard").map(_.source).collect { case git: ResolvedGit => git }
      .getOrElse(throw new ResolutionException("The verified resolution is missing the Chipyard checkout."))
    val binaries = payload.map(workspace.resolve(_).toAbsolutePath.normalize())
    binaries.foreach { binary =>
      if (!Files.isRegularFile(binary)) throw new ResolutionException(s"Missing simulation payload: $binary")
    }

    val toolchainRoot = toolchain.toAbsolutePath.normalize()
    val builds = target.toAbsolutePath.normalize().resolve("hardware/builds")
    val sourceCache = cacheDirectory.toAbsolutePath.normalize()
    if (Seq(toolchainRoot, builds, sourceCache).exists(path => !path.toString.matches(makePath)))
      throw new ResolutionException("Chipyard's Make flow requires build, toolchain and cache paths without whitespace or shell metacharacters.")
    val environment = toolEnvironment(toolchainRoot)
    val versions = toolVersions(toolchainRoot, environment, platform.toolchain, standalone)
    val lockFingerprint = ResolutionFiles.lockFingerprint(resolution)

    Files.createDirectories(builds)
    val staging = Files.createTempDirectory(builds, ".prepare-")
    val (runDirectory, packageName) = try {
      val checkout = staging.resolve("chipyard")
      val git = new GitCache(sourceCache)
      git.copyCheckout(platformSources.checkout, checkout)
      platformSources.submodules.foreach { case (path, source) => git.copyCheckout(source, checkout.resolve(path)) }

      val generated = checkout.resolve("generators/hardware-workspace")
      val system = generated.resolve("system")
      copySourceTree(workspace.resolve(mainSources), system.resolve(mainSources))
      val owner = SourceSelection.Project("workspace", workspace, system)
      val entryTarget = system.resolve("experiment").resolve(entryFile)
      Files.createDirectories(entryTarget.getParent)
      val selected = entry.map { path =>
        copySourceTree(path.getParent, entryTarget.getParent)
        SourceSelection.ScalaSource(entryTarget, FilesSupport.portable(workspace.relativize(path)))
      }
      val entryPackage = if (standalone) {
        val namespace = SourceSelection.entryPackage(SourceSelection.moduleEntry(owner), "Module", module = true)
        Files.write(entryTarget, standaloneDriver(namespace).getBytes(UTF_8))
        namespace
      } else SourceSelection.entryPackage(selected.get, "System", module = false)

      val external = nodes.filter(_.recipe.isEmpty)
      val dependencies = external.map { node =>
        val project = generated.resolve(node.id + "-source")
        val main = node.source.directory.resolve(mainSources)
        if (!Files.isDirectory(main))
          throw new ResolutionException(s"Dependency '${node.id}' must provide src/main for the Chipyard source recipe.")
        copySourceTree(main, project.resolve(mainSources))
        node.source match {
          case git: ResolvedGit =>
            git.submodules.foreach { case (path, checkout) =>
              val relative = Paths.get(path)
              if (relative.startsWith(mainSources)) copySourceTree(checkout.directory, project.resolve(relative))
            }
          case _ =>
        }
        SourceSelection.Project(s"dependency/${node.id}", node.source.directory, project)
      }

      val experimentSources = entry.toVector.flatMap { path =>
        val directory = entryTarget.getParent
        SourceSelection.scalaFiles(directory).map { source =>
          val relative = workspace.relativize(path.getParent).resolve(directory.relativize(source))
          SourceSelection.ScalaSource(source, FilesSupport.portable(relative))
        }
      }
      val selection = SourceSelection.plan(owner +: dependencies, experimentSources, unit => {
        val destination = unit.owner.staged.resolve(unit.relativeDirectory)
        copySourceTree(unit.directory.resolve(mainSources), destination.resolve(mainSources))
      })
      external.foreach { node =>
        node.source match {
          case local: ResolvedLocal
              if LocalInputs.resolve(workspace, local.directory).fingerprint != local.fingerprint =>
            throw new ResolutionException(s"Local dependency '${node.id}' changed while preparing the build. Run hardwareResolve.")
          case _ =>
        }
      }
      selection.units.foreach { case (unit, reason) =>
        reason.foreach(by => info(s"Selected ${unit.packageName} via ${by.source}:${by.line} (${by.imported})"))
      }
      val overlay = sbtProjects(nodes, external, resolution.roots, selection.selected, owner, dependencies)
      Files.write(checkout.resolve("build.sbt"), overlay.getBytes(UTF_8), StandardOpenOption.APPEND)
      val buildFingerprint = digest(Seq(lockFingerprint, buildName, entryPackage, overlay,
        LocalInputs.resolve(checkout, generated).fingerprint) ++ versions.map { case (tool, version) => s"$tool=$version" })

      val binary = binaries.map { original =>
        val copied = staging.resolve(simulationBinary)
        Files.createDirectories(copied.getParent)
        Files.copy(original, copied)
        original -> copied
      }
      val mode = if (standalone) "circt-standalone" else if (binary.isDefined) "verilator-simulate" else "verilator-elaborate"
      val symbol = if (standalone) "Module" else "System"
      val manifest = ResolutionFiles.obj(Vector(
        "schemaVersion" -> "1",
        "experiment" -> experiment.map(ResolutionFiles.string).getOrElse("null"),
        "configuration" -> ResolutionFiles.string(s"$entryPackage.$symbol"),
        "recipe" -> ResolutionFiles.string(platform.id),
        "backend" -> ResolutionFiles.string(mode),
        "lockFingerprint" -> ResolutionFiles.string(lockFingerprint),
        "buildFingerprint" -> ResolutionFiles.string(buildFingerprint),
        "toolchain" -> ResolutionFiles.obj(versions.map { case (name, version) => name -> ResolutionFiles.string(version) }),
        "sourceSelection" -> selection.json,
        "payload" -> binary.map { case (original, copied) => ResolutionFiles.obj(Vector(
          "file" -> ResolutionFiles.string(original.getFileName.toString),
          "sha256" -> ResolutionFiles.string(FilesSupport.hex(FilesSupport.fileDigest(copied, new Array[Byte](65536)))),
          "randomSeed" -> "1"
        )) }.getOrElse("null")
      ))
      ResolutionFiles.writeJson(staging.resolve("run.json"), manifest)
      val destination = builds.resolve(buildFingerprint).resolve(UUID.randomUUID().toString)
      Files.createDirectories(destination.getParent)
      Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE) -> entryPackage
    } finally {
      if (Files.exists(staging)) FilesSupport.deleteTree(staging)
    }

    val settings = makeEnvironment(runDirectory, sourceCache, jobs, environment)
    val simulation = binaries.map(_ => runDirectory.resolve(simulationBinary))
    val checkout = runDirectory.resolve("chipyard")
    val firtool = toolchainRoot.resolve("riscv-tools/bin/firtool")
    val command = if (standalone) {
      val rtl = Files.createDirectories(runDirectory.resolve("rtl"))
      Vector(toolchainRoot.resolve("bin/java").toString, "-jar", checkout.resolve("scripts/sbt-launch.jar").toString) ++
        sbtOptions(runDirectory, sourceCache) :+ s"hardwareSystem/runMain $standaloneMain $rtl $firtool"
    } else Vector(
      toolchainRoot.resolve("bin/make").toString, s"-j$jobs", "SBT_PROJECT=hardwareSystem",
      s"FIRTOOL_BIN=$firtool",
      s"CXX=${toolchainRoot.resolve("bin/g++")}",
      "GENERATOR_PACKAGE=chipyard", s"CONFIG_PACKAGE=$packageName", "CONFIG=System") ++
      simulation.map(path => Vector("run-binary-fast", s"BINARY=$path", "RANDOM_SEED=1")).getOrElse(Vector("verilog"))

    info(s"Generated Chipyard build: $runDirectory")
    val directory = if (standalone) checkout else checkout.resolve("sims/verilator")
    val exited = Process(withoutEnvironment(inheritedBuildOverrides) ++ command, directory.toFile, settings: _*)
      .!(ProcessLogger(info, warn))
    if (exited != 0)
      throw new ResolutionException(s"Chipyard $buildName failed with exit status $exited. Generated build: $runDirectory")
    runDirectory
  }

  private def standaloneDriver(namespace: String): String =
    s"""package chipyardgenerator.internal
       |
       |object Standalone {
       |  def main(args: Array[String]): Unit = {
       |    circt.stage.ChiselStage.emitSystemVerilogFile(
       |      _root_.$namespace.Module.create(), Array("--target-dir", args(0), "--firtool-binary-path", args(1)))
       |  }
       |}
       |""".stripMargin

  private def sbtProjects(nodes: Vector[ResolvedNode], external: Vector[ResolvedNode], roots: Vector[String],
      selected: Vector[SourceSelection.UnitSource], owner: SourceSelection.Project,
      externalOwners: Vector[SourceSelection.Project]): String = {
    val projects = nodes.map { node =>
      val project = if (node.recipe.isEmpty) {
        "hardwareSource_" + FilesSupport.sha256(node.id.getBytes(UTF_8)).take(16)
      } else builtInProjects(node.name)
      node.id -> project
    }.toMap

    def depends(ids: Vector[String]): String =
      if (ids.isEmpty) "" else ids.map(projects).mkString(".dependsOn(", ", ", ")")

    def integrationSettings(project: SourceSelection.Project): String =
      selected.filter(_.owner == project).map { unit =>
        val dir = s"""baseDirectory.value / "integrations" / "${unit.name}" / "src" / "main" """
        s"""  .settings(
           |    Compile / unmanagedSourceDirectories += $dir / "scala",
           |    Compile / unmanagedResourceDirectories += $dir / "resources"
           |  )
           |""".stripMargin
      }.mkString

    val dependencies = external.zip(externalOwners).map { case (node, projectOwner) =>
      val project = projects(node.id)
      s"""lazy val $project = Project("$project", file("generators/hardware-workspace/${node.id}-source"))
         |  .settings(commonSettings)
         |  .settings(chiselSettings)
         |  ${depends(node.dependencies)}
         |""".stripMargin + integrationSettings(projectOwner)
    }
    val system =
      s"""lazy val hardwareSystem = Project("hardwareSystem", file("generators/hardware-workspace/system"))
         |  .settings(commonSettings)
         |  .settings(chiselSettings)
         |  .settings(Compile / unmanagedSourceDirectories += baseDirectory.value / "experiment")
         |  ${depends(("chipyard" +: roots).distinct)}
         |""".stripMargin + integrationSettings(owner)
    ("\n\n" + (dependencies :+ system).mkString("\n")).linesIterator.map(_.stripTrailing()).mkString("\n") + "\n"
  }

  private def copySourceTree(source: Path, destination: Path): Unit =
    if (Files.isDirectory(source)) {
      val root = source.toRealPath()
      val paths = Files.walk(root)
      try paths.iterator().asScala.filterNot(path => root.relativize(path).startsWith(".git")).foreach { path =>
        val copy = destination.resolve(root.relativize(path))
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(copy)
        else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          Files.copy(path, copy, StandardCopyOption.COPY_ATTRIBUTES)
        else if (Files.isSymbolicLink(path) && !Files.readSymbolicLink(path).isAbsolute &&
            path.toRealPath().startsWith(root))
          Files.copy(path, copy, LinkOption.NOFOLLOW_LINKS)
        else throw new ResolutionException(s"Unsupported source or resource in generated Chipyard build: $path")
      } finally paths.close()
    }

  private def toolEnvironment(root: Path): Vector[(String, String)] = {
    val riscv = root.resolve("riscv-tools")
    val path = Seq(root.resolve("bin").toString, riscv.resolve("bin").toString, sys.env.getOrElse("PATH", ""))
      .mkString(File.pathSeparator)
    Vector("PATH" -> path, "RISCV" -> riscv.toString, "JAVA_HOME" -> root.resolve("lib/jvm").toString,
      "MAKEFLAGS" -> "", "MFLAGS" -> "")
  }

  private def toolVersions(root: Path, environment: Vector[(String, String)],
      pins: Map[String, String], standalone: Boolean): Vector[(String, String)] = {
    def version(path: String, args: String*): String = {
      val executable = root.resolve(path)
      if (!Files.isExecutable(executable))
        throw new ResolutionException(s"Missing Chipyard toolchain executable: $executable")
      val output = scala.collection.mutable.ArrayBuffer.empty[String]
      val command = withoutEnvironment(Vector("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) ++
        (executable.toString +: args)
      val status = Process(command, None, environment: _*)
        .!(ProcessLogger(line => output.synchronized(output += line)))
      if (status != 0) throw new ResolutionException(s"Could not identify version of $executable (exit $status).")
      output.filter(_.nonEmpty).mkString("\n")
    }
    def matching(name: String, version: String, pattern: String): String = {
      if (!pattern.r.findFirstIn(version).isDefined)
        throw new ResolutionException(s"Chipyard requires $name ${pins(name)}. Found: $version")
      version
    }
    val jdk = matching("jdk", version("bin/java", "-version"), "version \"" + pins("jdk") + """[."-]""")
    val firtool = matching("circt", version("riscv-tools/bin/firtool", "--version"),
      "CIRCT " + java.util.regex.Pattern.quote(pins("circt")) + "(?:\\s|$)")
    val tools = pins ++ Map("jdk" -> jdk, "circt" -> firtool)
    val actual = if (standalone) tools - "verilator" else {
      val verilator = matching("verilator", version("bin/verilator", "--version"),
        "Verilator " + java.util.regex.Pattern.quote(pins("verilator")) + "(?:\\s|$)")
      tools ++ Map("verilator" -> verilator, "cxx" -> version("bin/g++", "--version"),
        "make" -> version("bin/make", "--version"))
    }
    actual.toVector.sortBy(_._1)
  }

  private def makeEnvironment(run: Path, cache: Path, jobs: Int,
      environment: Vector[(String, String)]): Vector[(String, String)] = {
    val repos = run.resolve("repositories")
    val contents =
      """[repositories]
        |local
        |maven-central: https://repo.maven.apache.org/maven2
        |sbt-plugin-releases: https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
        |""".stripMargin
    Files.write(repos, contents.getBytes(UTF_8))
    Files.createDirectories(run.resolve("java-tmp"))
    Files.createDirectories(run.resolve("ivy2/local"))
    environment ++ Vector(
      "SBT_OPTS" -> sbtOptions(run, cache).mkString(" "),
      "COURSIER_CACHE" -> cache.resolve("scala-artifacts").toString,
      "JAVA_TOOL_OPTIONS" -> s"-Xmx14G -Xss8M -XX:ActiveProcessorCount=$jobs -Djava.io.tmpdir=$run/java-tmp"
    )
  }

  private def sbtOptions(run: Path, cache: Path): Vector[String] =
    Vector(s"-Dsbt.ivy.home=$run/ivy2", s"-Dsbt.global.base=$run/sbt",
      s"-Dsbt.boot.directory=$cache/sbt-boot", "-Dsbt.override.build.repos=true",
      s"-Dsbt.repository.config=$run/repositories", "-Dsbt.supershell=false", "-Dsbt.color=false",
      "-Dsbt.server.autostart=false")

  private def digest(parts: Seq[String]): String =
    FilesSupport.sha256(parts.mkString("\u0000").getBytes(UTF_8))

  private def withoutEnvironment(names: Vector[String]): Vector[String] =
    "env" +: names.flatMap(name => Vector("-u", name))
}
