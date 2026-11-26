package chipyard.generator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

object ResolverChecks {
  def main(args: Array[String]): Unit = {
    val temp = Files.createTempDirectory("hardware-resolver-")
    try run(temp)
    finally FilesSupport.deleteTree(temp)
  }

  private def run(temp: Path): Unit = {
    val workspace = Files.createDirectory(temp.resolve("workspace"))
    val local = Files.createDirectory(workspace.resolve("generator"))
    write(local.resolve("Module.scala"), "object Module")
    val targetPackage = Files.createDirectories(local.resolve("src/main/scala/target"))
    write(targetPackage.resolve("Source.scala"), "object BuildInput")
    val repository = Files.createDirectory(temp.resolve("generator.git"))
    val baseline = commit(repository, "first")
    val experimental = commit(repository, "second")
    val cacheRoot = temp.resolve("cache")
    val cache = new GitCache(cacheRoot)
    val resolver = new Resolver(cache)
    val url = repository.toUri.toString
    val oldCore = Generator.git("core", url, baseline, "baseline")
    val newCore = Generator.git("core", url, experimental, "experimental")
    val localCore = Generator.local("local", local.toFile)
    val roots = Vector(localCore, oldCore, newCore)

    val lock = workspace.resolve("hardware.lock")
    val target = workspace.resolve("target")
    println("Resolves a local generator and two aliased Git revisions into one graph.")
    val resolved = resolver.resolve(workspace, roots)
    val plan = ResolutionFiles.write(workspace, target, resolved, updateLock = true)
    val locked = Files.readAllBytes(lock).toVector
    assert(resolved.nodes.map(_.id) == Vector("baseline", "experimental", "local"))
    val checkouts = resolved.nodes.collect {
      case ResolvedNode(_, _, _, ResolvedGit(checkout, _), _, _) => checkout
    }
    assert(checkouts.map(_.commit) == Vector(baseline, experimental))
    assert(checkouts.map(_.directory).distinct.size == 2)

    println("Writes a portable, byte-identical lockfile when declaration order changes.")
    assert(!new String(locked.toArray, UTF_8).contains(cacheRoot.toString))
    assert(ResolutionFiles.write(workspace, target, resolver.resolve(workspace, roots.reverse), updateLock = true) == plan)
    assert(Files.readAllBytes(lock).toVector == locked)

    println("Resolves a shared transitive dependency once and records both incoming edges.")
    val shared = oldCore.copy(alias = None)
    val diamond = resolver.resolve(workspace, Vector(
      localCore.copy(name = "left").requires(shared),
      localCore.copy(name = "right").requires(shared)
    ))
    assert(diamond.roots == Vector("left", "right"))
    assert(diamond.nodes.map(node => node.id -> node.dependencies) ==
      Vector("core" -> Vector.empty, "left" -> Vector("core"), "right" -> Vector("core")))

    println("Reuses one cached Git revision under two aliases in independent workspaces.")
    val otherWorkspace = Files.createDirectory(temp.resolve("other-workspace"))
    val imported = resolver.resolve(otherWorkspace, Vector(oldCore.copy(alias = Some("comparison"))))
    val otherPlan = ResolutionFiles.write(otherWorkspace, otherWorkspace.resolve("target"), imported, updateLock = true)
    assert(imported.nodes.map(_.id) == Vector("comparison"))
    assert(imported.nodes.head.source.directory == checkouts.head.directory)
    assert(otherPlan.startsWith(otherWorkspace))
    assert(Files.readAllBytes(otherWorkspace.resolve("hardware.lock")).toVector != locked)
    assert(Files.readAllBytes(lock).toVector == locked)

    println("Rejects a stale lockfile when a still-cached alias is removed from the declarations.")
    val reduced = resolver.resolve(workspace, Vector(localCore, newCore))
    assert(reduced.nodes.map(_.id) == Vector("experimental", "local"))
    assert(Files.isDirectory(checkouts.head.directory))
    expect("out of date") { ResolutionFiles.write(workspace, target, reduced, updateLock = false) }
    assert(Files.readAllBytes(lock).toVector == locked)

    println("Rejects duplicate aliases in the workspace declarations.")
    expect("Duplicate dependency alias 'baseline'") {
      resolver.resolve(workspace, Vector(oldCore, newCore.copy(alias = oldCore.alias)))
    }
    println("Rejects conflicting shared revisions and reports both dependency paths.")
    expect("left -> core", "right -> core", baseline, experimental) {
      resolver.resolve(workspace, Vector(
        localCore.copy(name = "left").requires(oldCore.copy(alias = None)),
        localCore.copy(name = "right").requires(newCore.copy(alias = None))
      ))
    }

    println("Ignores generated output when fingerprinting local sources.")
    val generated = Files.createDirectories(local.resolve("target"))
    write(generated.resolve("generated.txt"), "generated output")
    assert(resolver.resolve(workspace, roots) == resolved)

    println("Detects local source changes and updates the lock only on explicit resolve.")
    write(targetPackage.resolve("Source.scala"), "object ChangedBuildInput")
    val changed = resolver.resolve(workspace, roots)
    expect("out of date") { ResolutionFiles.write(workspace, target, changed, updateLock = false) }
    assert(Files.readAllBytes(lock).toVector == locked)
    assert(ResolutionFiles.write(workspace, target, changed, updateLock = true) != plan)

    println("Reuses pinned Git checkouts offline after the upstream repository disappears.")
    val offline = new Resolver(new GitCache(cacheRoot, offline = true))
    Files.move(repository, temp.resolve("unavailable.git"))
    assert(offline.resolve(workspace, roots) == changed)

    println("Reports a cache miss for an uncached revision in offline mode.")
    expect("Offline cache miss") {
      offline.resolve(workspace, Vector(Generator.git("uncached", url, "a" * 40)))
    }

    println("Resolves a platform recipe and its transitive submodule at the pinned commit.")
    val pinnedRepo = Files.createDirectory(temp.resolve("dependency.git"))
    val pinnedCommit = commit(pinnedRepo, "dependency")
    val platformRepo = Files.createDirectory(temp.resolve("platform.git"))
    commit(platformRepo, "platform")
    git(platformRepo, "-c", "protocol.file.allow=always", "submodule", "add", "-q", "../dependency.git", "generators/core")
    git(platformRepo, "add", ".")
    git(platformRepo, "commit", "-qm", "pin submodule")
    val platformSource = GitSource(platformRepo.toUri.toString, git(platformRepo, "rev-parse", "HEAD"))
    val recipe = PlatformRecipe("fixture-v1", platformSource, Map("scala" -> "2.13.16"), Map(
      "core" -> RecipeProject("generators/core"),
      "wrapper" -> RecipeProject("generators/core", Vector("core"), shared = false)
    ))
    val platformGraph = resolver.resolve(workspace, Vector(Generator.chipyard("wrapper")), Some(recipe))
    assert(platformGraph.nodes.find(_.id == "wrapper").get.dependencies == Vector("chipyard", "core"))
    assert(platformGraph.nodes.find(_.id == "core").get.source.asInstanceOf[ResolvedGit].checkout.commit == pinnedCommit)
    val direct = cache.withSubmodules(cache.get(platformSource))
    assert(direct.submodules.map { case (path, source) => path -> source.commit } == Vector("generators/core" -> pinnedCommit))

    println("Rejects a toolchain version that conflicts with the platform recipe.")
    expect("Toolchain conflict for scala") {
      resolver.resolve(workspace, Nil, Some(recipe), Map("scala" -> "wrong"))
    }

    println("Shares one completed checkout across concurrent requests for the same revision.")
    val concurrentRoot = temp.resolve("concurrent-cache")
    val concurrentCache = new GitCache(concurrentRoot)
    val start = new CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(4)
    try {
      val jobs = Vector.fill(4)(pool.submit(new Callable[CachedSource] {
        override def call(): CachedSource = {
          start.await()
          concurrentCache.get(GitSource(pinnedRepo.toUri.toString, pinnedCommit))
        }
      }))
      start.countDown()
      assert(jobs.map(_.get(30, TimeUnit.SECONDS)).distinct.size == 1)
    } finally pool.shutdownNow()

    println("Cleans up an incomplete checkout after a Git fetch fails.")
    expect("Git fetch failed") {
      cache.get(GitSource(pinnedRepo.toUri.toString, "f" * 40))
    }
    val entries = Files.walk(cacheRoot)
    try assert(!entries.iterator().asScala.exists(path =>
      path.getFileName.toString.startsWith(".fetch-") || path.getFileName.toString == "f" * 40))
    finally entries.close()
    println("Resolver checks passed.")
  }

  private def commit(repository: Path, content: String): String = {
    if (!Files.exists(repository.resolve(".git"))) git(repository, "init", "-q", "--template=")
    write(repository.resolve("source.txt"), content)
    git(repository, "add", "source.txt")
    git(repository, "commit", "-qm", content)
    git(repository, "rev-parse", "HEAD")
  }

  private def git(directory: Path, arguments: String*): String =
    Process(Seq("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid") ++ arguments,
      directory.toFile, "GIT_CONFIG_NOSYSTEM" -> "1", "GIT_CONFIG_GLOBAL" -> "/dev/null")
      .!!(ProcessLogger(_ => ())).trim

  private def write(path: Path, text: String): Unit = Files.write(path, text.getBytes(UTF_8))

  private def expect(messages: String*)(action: => Any): Unit = {
    try {
      action
      throw new AssertionError(s"Expected a resolver failure containing ${messages.mkString(", ")}.")
    } catch {
      case error: ResolutionException =>
        messages.foreach(message => assert(error.getMessage.contains(message), error.getMessage))
    }
  }
}
