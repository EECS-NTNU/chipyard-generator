package chipyard.generator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

object MilestoneOneChecks {
  def main(args: Array[String]): Unit = {
    val temp = Files.createTempDirectory("hardware-m1-")
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
    val resolved = resolver.resolve(workspace, roots)
    val plan = ResolutionFiles.write(workspace, target, resolved, updateLock = true)
    val locked = Files.readAllBytes(lock).toVector
    assert(resolved.nodes.map(_.id) == Vector("baseline", "experimental", "local"))
    val checkouts = resolved.nodes.collect {
      case ResolvedNode(_, _, _, ResolvedGit(checkout, _), _, _) => checkout
    }
    assert(checkouts.map(_.commit) == Vector(baseline, experimental))
    assert(checkouts.map(_.directory).distinct.size == 2)
    assert(!new String(locked.toArray, UTF_8).contains(cacheRoot.toString))
    assert(ResolutionFiles.write(workspace, target, resolver.resolve(workspace, roots.reverse), updateLock = true) == plan)
    assert(Files.readAllBytes(lock).toVector == locked)

    expect("Duplicate dependency alias 'baseline'") {
      resolver.resolve(workspace, Vector(oldCore, newCore.copy(alias = oldCore.alias)))
    }
    expect("left -> core", "right -> core", baseline, experimental) {
      resolver.resolve(workspace, Vector(
        localCore.copy(name = "left").requires(oldCore.copy(alias = None)),
        localCore.copy(name = "right").requires(newCore.copy(alias = None))
      ))
    }

    val generated = Files.createDirectories(local.resolve("target"))
    write(generated.resolve("generated.txt"), "generated output")
    assert(resolver.resolve(workspace, roots) == resolved)
    write(targetPackage.resolve("Source.scala"), "object ChangedBuildInput")
    val changed = resolver.resolve(workspace, roots)
    expect("out of date") { ResolutionFiles.write(workspace, target, changed, updateLock = false) }
    assert(Files.readAllBytes(lock).toVector == locked)
    assert(ResolutionFiles.write(workspace, target, changed, updateLock = true) != plan)
    val offline = new Resolver(new GitCache(cacheRoot, offline = true))
    Files.move(repository, temp.resolve("unavailable.git"))
    assert(offline.resolve(workspace, roots) == changed)
    expect("Offline cache miss") {
      offline.resolve(workspace, Vector(Generator.git("uncached", url, "a" * 40)))
    }

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
    expect("Toolchain conflict for scala") {
      resolver.resolve(workspace, Nil, Some(recipe), Map("scala" -> "wrong"))
    }
    val direct = cache.withSubmodules(cache.get(platformSource))
    assert(direct.submodules.map { case (path, source) => path -> source.commit } == Vector("generators/core" -> pinnedCommit))

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

    expect("Git fetch failed") {
      cache.get(GitSource(pinnedRepo.toUri.toString, "f" * 40))
    }
    val entries = Files.walk(cacheRoot)
    try assert(!entries.iterator().asScala.exists(path =>
      path.getFileName.toString.startsWith(".fetch-") || path.getFileName.toString == "f" * 40))
    finally entries.close()
    println("Milestone 1 resolver checks passed.")
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
