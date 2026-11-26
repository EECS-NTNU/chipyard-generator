package chipyard.generator

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}

object ChipyardBuild {
  private val experimentName = "[A-Za-z0-9][A-Za-z0-9_-]*"
  private val makePath = "[A-Za-z0-9_./+@-]+"
  private val mainSources = Paths.get("src/main")
  private val scalaSources = mainSources.resolve("scala")
  private val simulationBinary = "payload/program.elf"
  private val inheritedBuildOverrides = Vector("USE_CHISEL7", "SBT", "MAKEFILES", "CLASSPATH_CACHE",
    "GENERATOR_CLASSPATH", "TAPEOUT_CLASSPATH", "SUB_PROJECT", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
  private val standaloneMain = "chipyardgenerator.internal.Standalone"
  private val builtInProjects = Map(
    "chipyard" -> "chipyard", "boom" -> "boom", "rocket-chip" -> "rocketchip",
    "hardfloat" -> "hardfloat", "diplomacy" -> "diplomacy", "pmu" -> "pmu",
    "cde" -> "cde", "firesim" -> "midas_target_utils"
  )

  private val simulatorDirectory = "chipyard/sims/verilator"
  private val simulatorName = "simulator-chipyard.harness-System"
  private val simulatorMarker = "simulator.ready"

  private final case class PreparedBuild(fingerprint: String, packageName: String, overlay: String, manifest: String)
  private final case class SimulatorBuild(directory: Path, packageName: String, toolchain: Path) {
    def executable: Path = directory.resolve(simulatorDirectory).resolve(simulatorName)
  }

  /** Stages locked sources, generates the requested hardware, and publishes a simulator only after Make succeeds. */
  def build(
      workspace: Path,
      target: Path,
      cacheDirectory: Path,
      resolution: Resolution,
      experiment: Option[String],
      toolchain: Path,
      jobs: Int,
      buildSimulator: Boolean,
      info: String => Unit,
      warn: String => Unit
  ): Path = {
    val platform = buildPlatform(resolution)
    val nodes = resolution.nodes.sortBy(_.id)
    val platformSources = nodes.find(_.id == "chipyard").map(_.source).collect { case git: ResolvedGit => git }
      .getOrElse(throw new ResolutionException("The verified resolution is missing the Chipyard checkout."))
    val builds = target.toAbsolutePath.normalize().resolve("hardware/builds")
    val toolchainRoot = toolchain.toAbsolutePath.normalize()
    val sourceCache = cacheDirectory.toAbsolutePath.normalize()
    checkMakePaths(toolchainRoot, builds, sourceCache)
    val standalone = experiment.isEmpty
    val environment = toolEnvironment(toolchainRoot)
    val versions = toolVersions(toolchainRoot, environment, platform.toolchain, standalone)
    val backend = if (standalone) "circt-standalone" else if (buildSimulator) "verilator-build" else "verilator-elaborate"

    val (directory, prepared) = withStaging(builds) { staging =>
      val checkout = staging.resolve("chipyard")
      val git = new GitCache(sourceCache)
      git.copyCheckout(platformSources.checkout, checkout)
      platformSources.submodules.foreach { case (path, source) => git.copyCheckout(source, checkout.resolve(path)) }
      val inputs = prepareSources(workspace, checkout, resolution, experiment, versions, backend, info)
      Files.write(checkout.resolve("build.sbt"), inputs.overlay.getBytes(UTF_8), StandardOpenOption.APPEND)
      ResolutionFiles.writeJson(staging.resolve("run.json"), inputs.manifest)
      val destination = builds.resolve(inputs.fingerprint).resolve(UUID.randomUUID().toString)
      Files.createDirectories(destination.getParent)
      Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE) -> inputs
    }

    val settings = makeEnvironment(directory, sourceCache, jobs, environment)
    val checkout = directory.resolve("chipyard")
    val firtool = toolchainRoot.resolve("riscv-tools/bin/firtool")
    val simulator = SimulatorBuild(directory, prepared.packageName, toolchainRoot)
    val command = if (standalone) {
      val rtl = Files.createDirectories(directory.resolve("rtl"))
      Vector(toolchainRoot.resolve("bin/java").toString, "-jar", checkout.resolve("scripts/sbt-launch.jar").toString) ++
        sbtOptions(directory, sourceCache) :+ s"hardwareSystem/runMain $standaloneMain $rtl $firtool"
    } else {
      val goal = if (buildSimulator) Vector(s"sim=${simulator.executable}", "default") else Vector("verilog")
      makeCommand(simulator, jobs) ++ goal
    }

    info(s"Generated Chipyard build: $directory")
    val workingDirectory = if (standalone) checkout else directory.resolve(simulatorDirectory)
    val exited = Process(withoutEnvironment(inheritedBuildOverrides) ++ command, workingDirectory.toFile, settings: _*)
      .!(ProcessLogger(info, warn))
    if (exited != 0)
      throw new ResolutionException(s"Chipyard ${experiment.getOrElse("standalone")} failed with exit status $exited. Generated build: $directory")
    if (buildSimulator) {
      if (!isSimulatorExecutable(simulator.executable))
        throw new ResolutionException(s"Chipyard did not produce the simulator: ${simulator.executable}")
      val record = Seq(experiment.get, simulator.packageName, toolchainRoot.toString).mkString("", "\n", "\n")
      FilesSupport.writeAtomically(directory.resolve(simulatorMarker), record.getBytes(UTF_8))
      info(s"Verilator simulator: ${simulator.executable}")
    }
    directory
  }

  /** Finds a completed simulator for the exact sources, verified lock, and tool versions in use. */
  def findSimulator(workspace: Path, target: Path, resolution: Resolution, experiment: String, toolchain: Path): Path = {
    val builds = target.toAbsolutePath.normalize().resolve("hardware/builds")
    def missing = new ResolutionException(s"No matching simulator build. Run presolve build $experiment first.")
    if (!Files.isDirectory(builds)) throw missing
    val root = toolchain.toAbsolutePath.normalize()
    val versions = toolVersions(root, toolEnvironment(root), buildPlatform(resolution).toolchain, standalone = false)
    val fingerprint = withStaging(builds) { staging =>
      prepareSources(workspace, staging, resolution, Some(experiment), versions, "verilator-build", _ => ()).fingerprint
    }
    val matching = builds.resolve(fingerprint)
    if (!Files.isDirectory(matching)) throw missing
    val entries = Files.list(matching)
    try {
      entries.iterator().asScala.filter(path => completedSimulator(path, experiment).isDefined)
        .toVector.sortBy(path => (Files.getLastModifiedTime(path.resolve(simulatorMarker)).toInstant, path.toString))
        .lastOption.getOrElse(throw missing)
    } finally entries.close()
  }

  /** Runs a completed simulator with its recorded toolchain and writes independent payload and log artifacts. */
  def simulate(workspace: Path, target: Path, experiment: String, payload: Path, build: Path,
      info: String => Unit, warn: String => Unit): Path = {
    val buildDirectory = workspace.resolve(build).toAbsolutePath.normalize()
    val simulator = completedSimulator(buildDirectory, experiment).getOrElse(
      throw new ResolutionException(s"No completed simulator build for '$experiment' at $buildDirectory."))
    val original = workspace.resolve(payload).toAbsolutePath.normalize()
    if (!Files.isRegularFile(original)) throw new ResolutionException(s"Missing simulation payload: $original")
    val runs = target.toAbsolutePath.normalize().resolve("hardware/runs")
    checkMakePaths(buildDirectory, simulator.toolchain, runs)
    val directory = runs.resolve(UUID.randomUUID().toString)
    val binary = directory.resolve(simulationBinary)
    Files.createDirectories(binary.getParent)
    Files.copy(original, binary)
    val manifest = ResolutionFiles.obj(Vector(
      "schemaVersion" -> "1",
      "experiment" -> ResolutionFiles.string(experiment),
      "backend" -> ResolutionFiles.string("verilator-simulate"),
      "buildDirectory" -> ResolutionFiles.string(buildDirectory.toString),
      "payload" -> ResolutionFiles.obj(Vector(
        "file" -> ResolutionFiles.string(original.getFileName.toString),
        "sha256" -> ResolutionFiles.string(FilesSupport.hex(FilesSupport.fileDigest(binary, new Array[Byte](65536)))),
        "randomSeed" -> "1"))
    ))
    ResolutionFiles.writeJson(directory.resolve("run.json"), manifest)

    info(s"Using Chipyard build: $buildDirectory")
    info(s"Generated Chipyard run: $directory")
    val command = makeCommand(simulator, jobs = 1) ++ Vector(
      "run-binary-fast", "BREAK_SIM_PREREQ=1", "SIM_PREREQ=", s"sim=${simulator.executable}",
      s"output_dir=${directory.resolve("output")}", s"BINARY=$binary", "RANDOM_SEED=1")
    val exited = Process(withoutEnvironment(inheritedBuildOverrides) ++ command,
      buildDirectory.resolve(simulatorDirectory).toFile, toolEnvironment(simulator.toolchain): _*)
      .!(ProcessLogger(info, warn))
    if (exited != 0)
      throw new ResolutionException(s"Chipyard $experiment failed with exit status $exited. Generated run: $directory")
    directory
  }

  private def completedSimulator(directory: Path, experiment: String): Option[SimulatorBuild] = {
    val marker = directory.resolve(simulatorMarker)
    if (!Files.isRegularFile(marker) || !Files.isRegularFile(directory.resolve("run.json"))) return None
    Files.readAllLines(marker, UTF_8).asScala.toList match {
      case `experiment` :: namespace :: toolchain :: Nil
          if namespace.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*") &&
            toolchain.matches(makePath) && Paths.get(toolchain).isAbsolute =>
        val build = SimulatorBuild(directory, namespace, Paths.get(toolchain))
        Option(build).filter(candidate => isSimulatorExecutable(candidate.executable))
      case _ => None
    }
  }

  private def isSimulatorExecutable(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)

  private def makeCommand(build: SimulatorBuild, jobs: Int): Vector[String] =
    Vector(build.toolchain.resolve("bin/make").toString, s"-j$jobs", "SBT_PROJECT=hardwareSystem",
      s"FIRTOOL_BIN=${build.toolchain.resolve("riscv-tools/bin/firtool")}",
      s"CXX=${build.toolchain.resolve("bin/g++")}",
      "GENERATOR_PACKAGE=chipyard", s"CONFIG_PACKAGE=${build.packageName}", "CONFIG=System")

  private def checkMakePaths(paths: Path*): Unit =
    if (paths.exists(path => !path.toString.matches(makePath)))
      throw new ResolutionException("Chipyard's Make flow requires build, toolchain and cache paths without whitespace or shell metacharacters.")

  private def withStaging[A](builds: Path)(operation: Path => A): A = {
    Files.createDirectories(builds)
    val staging = Files.createTempDirectory(builds, ".prepare-")
    try operation(staging)
    finally if (Files.exists(staging)) FilesSupport.deleteTree(staging)
  }

  private def buildPlatform(resolution: Resolution): PlatformRecipe = resolution.platform.getOrElse(
    throw new ResolutionException("Set chipyardVersion before elaborating hardware."))

  private def prepareSources(workspace: Path, checkout: Path, resolution: Resolution, experiment: Option[String],
      versions: Vector[(String, String)], backend: String, info: String => Unit): PreparedBuild = {
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
    val lockFingerprint = ResolutionFiles.lockFingerprint(resolution)
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

    val experimentSources = entry.toVector.flatMap { path =>
      val directory = entryTarget.getParent
      SourceSelection.scalaFiles(directory).map { source =>
        val relative = workspace.relativize(path.getParent).resolve(directory.relativize(source))
        SourceSelection.ScalaSource(source, FilesSupport.portable(relative))
      }
    }
    val dependencies = new BuildDependencies(nodes, resolution.roots, generated)
    val selection = SourceSelection.plan(
      owner +: dependencies.initialProjects, experimentSources, dependencies.materializeIntegration,
      activate = dependencies.activateImport, aliasProjects = dependencies.aliasProjects)
    val external = dependencies.activeNodes.filter(isSourceProject)
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
    val overlay = sbtProjects(dependencies.activeNodes, external, dependencies.systemDependencies,
      selection.selected, owner, external.map(node => dependencies.projects(node.id)))
    val fingerprint = digest(Seq(lockFingerprint, buildName, entryPackage, overlay,
      LocalInputs.resolve(checkout, generated).fingerprint) ++ versions.map { case (tool, version) => s"$tool=$version" })
    val symbol = if (standalone) "Module" else "System"
    val manifest = ResolutionFiles.obj(Vector(
      "schemaVersion" -> "1",
      "experiment" -> experiment.map(ResolutionFiles.string).getOrElse("null"),
      "configuration" -> ResolutionFiles.string(s"$entryPackage.$symbol"),
      "recipe" -> ResolutionFiles.string(buildPlatform(resolution).id),
      "backend" -> ResolutionFiles.string(backend),
      "lockFingerprint" -> ResolutionFiles.string(lockFingerprint),
      "buildFingerprint" -> ResolutionFiles.string(fingerprint),
      "toolchain" -> ResolutionFiles.obj(versions.map { case (name, version) => name -> ResolutionFiles.string(version) }),
      "sourceSelection" -> selection.json,
      "aliases" -> dependencies.aliasManifest,
      "payload" -> "null"
    ))
    PreparedBuild(fingerprint, entryPackage, overlay, manifest)
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

  private def aliasOf(node: ResolvedNode): Option[String] = node.alias.filter(_ != node.name)

  private def isSourceProject(node: ResolvedNode): Boolean = node.recipe.isEmpty || aliasOf(node).nonEmpty

  private final class BuildDependencies(nodes: Vector[ResolvedNode], roots: Vector[String], generated: Path) {
    private val byId = nodes.map(node => node.id -> node).toMap
    private val aliases = nodes.filter(node => aliasOf(node).nonEmpty).map(node => node.id -> node).toMap
    private val active = mutable.Set.empty[String]
    private val byName = mutable.Map.empty[String, String]
    private val importedRoots = mutable.Set.empty[String]
    private val originalPackages = mutable.Map.empty[String, String]
    val projects = mutable.Map.empty[String, SourceSelection.Project]

    val aliasProjects: Map[String, String] = aliases.keysIterator.map(id => id -> projectLabel(id)).toMap

    def initialProjects: Vector[SourceSelection.Project] = activate(roots.filterNot(aliases.contains))

    def activeNodes: Vector[ResolvedNode] = nodes.filter(node => active(node.id))

    def systemDependencies: Vector[String] = (roots.filter(active) ++ importedRoots.toVector.sorted).distinct

    def activateImport(qualified: String, reason: SourceSelection.Reason): Vector[SourceSelection.Project] = {
      val alias = qualified.takeWhile(_ != '.')
      if (!aliases.contains(alias)) Vector.empty
      else {
        if (!reason.source.startsWith("dependency/")) importedRoots += alias
        activate(Vector(alias))
      }
    }

    def materializeIntegration(unit: SourceSelection.UnitSource): Unit = {
      val destination = unit.owner.staged.resolve(unit.relativeDirectory)
      copySourceTree(unit.directory.resolve(mainSources), destination.resolve(mainSources))
      val id = unit.owner.id.stripPrefix("dependency/")
      if (unit.owner.id.startsWith("dependency/")) {
        originalPackages.get(id).foreach(original => relocate(unit.owner, destination, original, id))
      }
    }

    def aliasManifest: String = ResolutionFiles.array(activeNodes.filter(node => aliases.contains(node.id)).map { node =>
      val revision = node.source match {
        case git: ResolvedGit => ResolutionFiles.string(git.checkout.commit)
        case _: ResolvedLocal => "null"
      }
      ResolutionFiles.obj(Vector(
        "alias" -> ResolutionFiles.string(node.id),
        "generator" -> ResolutionFiles.string(node.name),
        "originalPackage" -> ResolutionFiles.string(originalPackages(node.id)),
        "package" -> ResolutionFiles.string(node.id),
        "revision" -> revision))
    })

    private def projectLabel(id: String): String = s"dependency/$id"

    private def activate(ids: Vector[String]): Vector[SourceSelection.Project] = {
      val added = Vector.newBuilder[SourceSelection.Project]
      def include(id: String): Unit = if (active.add(id)) {
        val node = byId(id)
        byName.get(node.name).foreach { previous =>
          throw new ResolutionException(s"Only one revision of generator '${node.name}' may be selected per experiment. " +
            s"Selected dependency aliases '$previous' and '$id'.")
        }
        byName(node.name) = id
        node.dependencies.foreach(include)
        if (isSourceProject(node)) {
          val project = stage(node)
          projects(id) = project
          added += project
        }
      }
      ids.foreach(include)
      added.result()
    }

    private def stage(node: ResolvedNode): SourceSelection.Project = {
      val directory = generated.resolve(node.id + "-source")
      val main = node.source.directory.resolve(mainSources)
      if (!Files.isDirectory(main))
        throw new ResolutionException(s"Dependency '${node.id}' must provide src/main for the Chipyard source recipe.")
      copySourceTree(main, directory.resolve(mainSources))
      node.source match {
        case git: ResolvedGit =>
          git.submodules.foreach { case (path, checkout) =>
            val relative = Paths.get(path)
            if (relative.startsWith(mainSources)) copySourceTree(checkout.directory, directory.resolve(relative))
          }
        case _: ResolvedLocal =>
      }
      val project = SourceSelection.Project(projectLabel(node.id), node.source.directory, directory)
      aliasOf(node).foreach { namespace =>
        AliasNamespace.validateAlias(namespace)
        val sources = SourceSelection.scalaFiles(directory.resolve(scalaSources)).map(project.source)
        val original = AliasNamespace.root(sources, project.id)
        originalPackages(node.id) = original
        sources.foreach(source => AliasNamespace.relocate(source, original, namespace))
      }
      project
    }

    private def relocate(project: SourceSelection.Project, directory: Path, original: String, namespace: String): Unit =
      SourceSelection.scalaFiles(directory.resolve(scalaSources)).foreach { path =>
        AliasNamespace.relocate(project.source(path), original, namespace)
      }
  }

  private def sbtProjects(nodes: Vector[ResolvedNode], external: Vector[ResolvedNode], roots: Vector[String],
      selected: Vector[SourceSelection.UnitSource], owner: SourceSelection.Project,
      externalOwners: Vector[SourceSelection.Project]): String = {
    val projects = nodes.map { node =>
      val project = if (isSourceProject(node)) {
        "hardwareSource_" + FilesSupport.sha256(node.id.getBytes(UTF_8)).take(16)
      } else builtInProjects(node.name)
      node.id -> project
    }.toMap

    def depends(projectIds: Vector[String]): String =
      if (projectIds.isEmpty) "" else projectIds.mkString(".dependsOn(", ", ", ")")

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
      val declared = node.dependencies.map(projects)
      // Chipyard builds the PMU's TileLink support as a separate sbt project.
      val projectDependencies =
        if (node.name == "boom" && declared.contains("pmu")) declared :+ "pmu_tilelink" else declared
      s"""lazy val $project = Project("$project", file("generators/hardware-workspace/${node.id}-source"))
         |  .settings(commonSettings)
         |  .settings(chiselSettings)
         |  ${depends(projectDependencies)}
         |""".stripMargin + integrationSettings(projectOwner)
    }
    val system =
      s"""lazy val hardwareSystem = Project("hardwareSystem", file("generators/hardware-workspace/system"))
         |  .settings(commonSettings)
         |  .settings(chiselSettings)
         |  .settings(Compile / unmanagedSourceDirectories += baseDirectory.value / "experiment")
         |  ${depends(("chipyard" +: roots).distinct.map(projects))}
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
