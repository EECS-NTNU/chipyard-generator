import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import scala.sys.process._
import sbt._
import sbt.Keys._

object PinnedAccelerator {
  final case class Source(
    id: String,
    url: String,
    commit: String,
    sources: Seq[String],
    resources: Seq[String] = Nil,
    submodules: Seq[String] = Nil,
    gitOptions: Seq[String] = Nil,
    excludeSources: Set[String] = Set.empty,
    patches: Seq[String] = Nil
  ) {
    require(id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*"), s"Invalid accelerator ID: $id")
    require(commit.matches("[0-9a-f]{40}"), s"$id requires a full Git commit SHA")
  }

  val fetchPinnedSource = taskKey[File]("Fetch and verify the catalogued accelerator revision")
  val verifyPinnedSource = taskKey[File]("Verify the catalogued accelerator revision")

  def settings(source: Source): Seq[Def.Setting[_]] = Seq(
    fetchPinnedSource := fetch(cacheRoot((LocalRootProject / baseDirectory).value), source),
    verifyPinnedSource := verify(cacheRoot((LocalRootProject / baseDirectory).value) / source.id / source.commit, source),
    // Task-based file collection forces verification even with a warm incremental compile.
    Compile / sourceGenerators += Def.task {
      val checkout = verifyPinnedSource.value
      val root = (LocalRootProject / baseDirectory).value
      val output = (Compile / sourceManaged).value / source.id
      val selected = collect(checkout, source.sources, "*.scala").filterNot { file =>
        val path = IO.relativize(checkout, file).get
        source.excludeSources.exists(excluded => path == excluded || path.startsWith(excluded + "/"))
      }
      val patches = source.patches.map(root / _)
      val generate = FileFunction.cached(streams.value.cacheDirectory / "accelerator-port",
        FilesInfo.hash, FilesInfo.hash) { _ =>
        IO.delete(output)
        IO.createDirectory(output)
        IO.copy(selected.map(file => file -> (output / IO.relativize(checkout, file).get)))
        patches.foreach { patch =>
          if (Process(Seq("git", "apply", "--directory=" + output.getAbsolutePath,
            "--unsafe-paths", patch.getAbsolutePath), new File("/")).! != 0)
            sys.error(s"${source.id}: failed to apply $patch to ${source.commit}")
        }
        collect(output, Seq("."), "*.scala").toSet
      }
      generate((selected ++ patches :+ (checkout / ".git" / "HEAD")).toSet).toSeq.sorted
    }.taskValue,
    Compile / unmanagedResources := (Compile / unmanagedResources).dependsOn(verifyPinnedSource).value,
    Compile / unmanagedResourceDirectories ++= {
      val root = cacheRoot((LocalRootProject / baseDirectory).value) / source.id / source.commit
      source.resources.map(root / _)
    }
  )

  private def cacheRoot(root: File): File = root / ".accelerator-sources"

  private def collect(root: File, directories: Seq[String], glob: String): Seq[File] =
    directories.flatMap(path => ((root / path) ** glob).get.filter(_.isFile)).distinct

  private def git(dir: File, source: Source, args: String*): ProcessBuilder =
    Process(Seq("git") ++ source.gitOptions ++ Seq("-C", dir.getAbsolutePath) ++ args)

  private def verify(dir: File, source: Source): File = {
    def fail(reason: String): Nothing = sys.error(
      s"${source.id}: $reason ($dir). Run sbt '${source.id}/fetchPinnedSource'. " +
      "If the cache entry already exists, move it aside first to preserve local changes."
    )

    def read(args: String*): String = {
      val output = new StringBuilder
      val errors = new StringBuilder
      val status = git(dir, source, args: _*).!(ProcessLogger(
        line => { output.append(line).append('\n'); () },
        line => { errors.append(line).append('\n'); () }
      ))
      if (status != 0) fail(s"git ${args.mkString(" ")} failed: ${errors.toString.trim}")
      output.toString.trim
    }

    if (!Files.exists((dir / ".git").toPath)) fail("missing checkout")
    val head = read("rev-parse", "HEAD")
    if (head != source.commit) fail(s"expected ${source.commit}, found $head")
    val origin = read("config", "--get", "remote.origin.url")
    if (origin != source.url) fail(s"expected origin ${source.url}, found $origin")
    if (read("status", "--porcelain", "--untracked-files=all", "--ignored", "--ignore-submodules=none").nonEmpty)
      fail("checkout contains local changes or extra files")
    if (source.submodules.nonEmpty) {
      val statuses = read((Seq("submodule", "status", "--recursive", "--") ++ source.submodules): _*)
      // The leading space denotes the pinned state, but read trims the first line.
      if (statuses.linesIterator.exists(line => line.startsWith("-") || line.startsWith("+") || line.startsWith("U")))
        fail("a required submodule is uninitialized or at a different revision")
      read("submodule", "foreach", "--recursive",
        """test -z "$(git status --porcelain --untracked-files=all --ignored --ignore-submodules=none)" """)
    }
    (source.sources ++ source.resources).foreach { path =>
      if (!(dir / path).isDirectory) fail(s"missing declared directory $path")
    }
    dir
  }

  private def fetch(cache: File, source: Source): File = {
    val parent = cache / source.id
    IO.createDirectory(parent)
    val destination = parent / source.commit
    val channel = FileChannel.open((parent / ".fetch.lock").toPath,
      StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try {
      val lock = channel.lock()
      try {
        if (!destination.exists()) {
          val staging = Files.createTempDirectory(parent.toPath, ".fetch-").toFile
          try {
            def run(args: String*): Unit =
              if (git(staging, source, args: _*).! != 0)
                sys.error(s"${source.id}: git ${args.mkString(" ")} failed for ${source.url}")

            run("init", "--quiet")
            run("remote", "add", "origin", source.url)
            run("fetch", "--no-tags", "--depth=1", "origin", source.commit)
            run("checkout", "--quiet", "--detach", source.commit)
            if (source.submodules.nonEmpty)
              run((Seq("submodule", "update", "--init", "--recursive", "--") ++ source.submodules): _*)
            verify(staging, source)
            Files.move(staging.toPath, destination.toPath, StandardCopyOption.ATOMIC_MOVE)
          } finally IO.delete(staging)
        }
        verify(destination, source)
      } finally lock.release()
    } finally channel.close()
  }
}
