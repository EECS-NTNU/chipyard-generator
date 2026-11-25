package chipyard.generator

import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.Locale
import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessIO}

final class GitCache(root: Path, offline: Boolean = false) {
  import GitCache._

  def get(source: GitSource): CachedSource = {
    val repository = canonicalRepository(source.url)
    val commit = pinnedCommit(source.ref)
    val parent = root.toAbsolutePath.normalize().resolve("sources").resolve(FilesSupport.sha256(repository.getBytes(UTF_8)))
    val entry = parent.resolve(commit)
    if (!complete(entry)) {
      if (Files.exists(entry) && !complete(entry))
        throw new ResolutionException(s"Incomplete Git cache entry at $entry. Remove it before retrying.")
      if (offline && !complete(entry))
        throw new ResolutionException(s"Offline cache miss for $repository at $commit. Run hardwareResolve online first.")
    }
    if (!complete(entry)) {
      Files.createDirectories(parent)
      val staging = Files.createTempDirectory(parent, ".fetch-")
      try {
        git(staging, "init", "--quiet", "--template=")
        git(staging, "remote", "add", "origin", repository)
        git(staging, "fetch", "--quiet", "--no-tags", "--depth=1", "origin", commit)
        val fetched = git(staging, "rev-parse", "FETCH_HEAD^{commit}").trim
        if (fetched != commit)
          throw new ResolutionException(s"Git resolved $repository at $fetched, expected $commit")
        git(staging, "checkout", "--quiet", "--detach", commit)
        if (rawPins(staging).nonEmpty) git(staging, "submodule", "init")
        readPins(staging)
        Files.createFile(staging.resolve(marker))
        try Files.move(staging, entry, StandardCopyOption.ATOMIC_MOVE)
        catch { case _: IOException if complete(entry) => () }
      } catch {
        case error: ResolutionException =>
          throw new ResolutionException(s"Could not cache $repository at $commit. ${error.getMessage}", error)
      } finally {
        if (Files.exists(staging)) FilesSupport.deleteTree(staging)
      }
    }
    CachedSource(repository, commit, entry, readPins(entry))
  }

  /** Resolves nested pins into separate cache entries without changing the parent checkout. */
  def withSubmodules(checkout: CachedSource): ResolvedGit = {
    def descend(parent: CachedSource, prefix: String): Vector[(String, CachedSource)] =
      parent.pins.flatMap { pin =>
        val path = prefix + pin.path
        val child = getSubmodule(parent, pin)
        (path -> child) +: descend(child, path + "/")
      }

    ResolvedGit(checkout, descend(checkout, ""))
  }

  private[generator] def getSubmodule(parent: CachedSource, pin: SubmodulePin): CachedSource = {
    if (pin.repository.startsWith("file:") && !parent.repository.startsWith("file:"))
      throw new ResolutionException(s"Remote source ${parent.repository} declares a local submodule at ${pin.path}")
    get(GitSource(pin.repository, pin.commit))
  }

  private def complete(path: Path): Boolean = Files.isRegularFile(path.resolve(marker))

  private def rawPins(directory: Path): Vector[(String, String)] =
    records(git(directory, "ls-tree", "-rz", "HEAD")).flatMap { row =>
      val fields = row.split("\t", 2)
      val metadata = fields(0).split(" ")
      if (metadata(0) == "160000") Some(fields(1) -> metadata(2)) else None
    }

  private def readPins(directory: Path): Vector[SubmodulePin] = {
    val pins = rawPins(directory)
    if (pins.isEmpty) Vector.empty else {
      val sectionsByPath = config(directory, "--blob", "HEAD:.gitmodules", "--get-regexp", "^submodule\\..*\\.path$")
        .map { case (key, value) => value -> key.stripSuffix(".path") }
      val urls = config(directory, "--local", "--get-regexp", "^submodule\\..*\\.url$")
      pins.map { case (path, commit) =>
        val key = sectionsByPath.getOrElse(path, throw new ResolutionException(s"Missing submodule declaration for $path"))
        val url = urls.getOrElse(key + ".url", throw new ResolutionException(s"Missing submodule URL for $path"))
        SubmodulePin(path, canonicalRepository(url), commit)
      }.sortBy(_.path)
    }
  }

  private def config(directory: Path, args: String*): Map[String, String] =
    records(git(directory, (Seq("config", "-z", "--no-includes") ++ args): _*)).map { row =>
      val pair = row.split("\n", 2)
      pair(0) -> pair(1)
    }.toMap
}

object GitCache {
  private val marker = ".git/hardware-source-v1"
  private def records(output: String): Vector[String] = output.split("\u0000").filter(_.nonEmpty).toVector

  def pinnedCommit(ref: String): String = {
    if (!ref.matches("[0-9a-fA-F]{40}"))
      throw new ResolutionException(s"Git ref '$ref' must be a full 40-character commit ID.")
    ref.toLowerCase(Locale.ROOT)
  }

  def canonicalRepository(url: String): String = {
    def fileUri(path: Path): String =
      path.toAbsolutePath.normalize().toUri.toASCIIString.stripSuffix("/")

    try {
      val uri = if (url.startsWith("/")) return fileUri(Paths.get(url)) else new URI(url).normalize()
      Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT)) match {
        case Some("file") if uri.getAuthority == null =>
          fileUri(Paths.get(uri))
        case Some(scheme @ ("https" | "http" | "ssh")) if uri.getHost != null &&
            uri.getQuery == null && uri.getFragment == null &&
            (uri.getUserInfo == null || (scheme == "ssh" && !uri.getUserInfo.contains(":"))) =>
          val port = Map("https" -> 443, "http" -> 80, "ssh" -> 22)
          val authority = new URI(scheme, uri.getUserInfo, uri.getHost.toLowerCase(Locale.ROOT),
            if (uri.getPort == port(scheme)) -1 else uri.getPort,
            null, null, null).toASCIIString
          new URI(authority + uri.getRawPath.stripSuffix("/")).toASCIIString
        case _ =>
          throw new ResolutionException("Git repositories require an absolute path or a file, HTTPS, HTTP or SSH URL without credentials.")
      }
    } catch {
      case error: java.net.URISyntaxException => throw new ResolutionException("Invalid Git repository URL.", error)
      case error: IllegalArgumentException => throw new ResolutionException("Invalid Git repository path.", error)
    }
  }

  private def git(directory: Path, args: String*): String = {
    val command = Seq("git", "-c", "core.hooksPath=/dev/null", "-c", "protocol.allow=never",
      "-c", "protocol.file.allow=always", "-c", "protocol.https.allow=always",
      "-c", "protocol.http.allow=always", "-c", "protocol.ssh.allow=always",
      "-c", "submodule.recurse=false", "-C", directory.toString) ++ args
    val builder = new java.lang.ProcessBuilder(command.asJava)
    val env = builder.environment()
    env.keySet().asScala.filter(key => key.startsWith("GIT_CONFIG") ||
      Set("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
        "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_TEMPLATE_DIR").contains(key)).toVector.foreach(env.remove)
    env.put("GIT_CONFIG_NOSYSTEM", "1")
    env.put("GIT_CONFIG_GLOBAL", "/dev/null")
    env.put("GIT_TERMINAL_PROMPT", "0")
    val output = new java.io.ByteArrayOutputStream
    val errors = new java.io.ByteArrayOutputStream
    val exit = Process(builder).run(new ProcessIO(
      _.close(),
      stream => try stream.transferTo(output) finally stream.close(),
      stream => try stream.transferTo(errors) finally stream.close()
    )).exitValue()
    if (exit != 0)
      throw new ResolutionException(s"Git ${args.head} failed in $directory:\n${new String(errors.toByteArray, UTF_8).trim}")
    new String(output.toByteArray, UTF_8)
  }
}
