package chipyard.generator

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}
import java.security.MessageDigest
import scala.collection.JavaConverters._

private[generator] object FilesSupport {
  def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString

  def sha256(bytes: Array[Byte]): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  def portable(path: Path): String = path.iterator().asScala.mkString("/")

  def deleteTree(path: Path): Unit = {
    val paths = Files.walk(path)
    try paths.iterator().asScala.toVector.reverse.foreach(Files.delete)
    finally paths.close()
  }

  def writeAtomically(path: Path, bytes: Array[Byte]): Unit = {
    Files.createDirectories(path.getParent)
    if (Files.exists(path) && java.util.Arrays.equals(Files.readAllBytes(path), bytes)) return
    val temporary = Files.createTempFile(path.getParent, ".hardware-", ".tmp")
    try {
      Files.write(temporary, bytes)
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally Files.deleteIfExists(temporary)
  }
}

private[generator] object LocalInputs {
  private val generatedDirectories = Set("target", ".bsp", ".bloop", ".metals")

  def resolve(workspace: Path, path: Path): ResolvedLocal = {
    val input = workspace.resolve(path).normalize()
    if (!Files.isDirectory(input))
      throw new ResolutionException(s"Local source directory does not exist: $input")
    val directory = input.toRealPath()
    val identity = if (workspace.getRoot == directory.getRoot)
      FilesSupport.portable(workspace.relativize(directory)) else directory.toString
    ResolvedLocal(if (identity.isEmpty) "." else identity, fingerprint(directory), directory)
  }

  private def included(path: Path): Boolean = {
    val parts = path.iterator().asScala.map(_.toString).toVector
    val buildPath = parts.dropWhile(_ == "project")
    !parts.contains(".git") && parts != Vector("hardware.lock") &&
      !buildPath.headOption.exists(generatedDirectories)
  }

  private def fingerprint(root: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = new Array[Byte](65536)

    def add(text: String): Unit = {
      val bytes = text.getBytes(UTF_8)
      digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
    }

    def visit(directory: Path): Unit = {
      val files = Files.list(directory)
      val children = try files.iterator().asScala.toVector.sortBy(_.getFileName.toString) finally files.close()
      children.filter(file => included(root.relativize(file))).foreach { file =>
        val relative = root.relativize(file)
        add(FilesSupport.portable(relative))
        if (Files.isSymbolicLink(file)) {
          val target = Files.readSymbolicLink(file)
          val real = file.toRealPath()
          if (target.isAbsolute || !real.startsWith(root) || !included(root.relativize(real)))
            throw new ResolutionException(s"Local input symlink $file must point to a relative input inside $root")
          add("link")
          add(FilesSupport.portable(target))
        } else if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
          add("directory")
          visit(file)
        } else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          add(if (Files.isExecutable(file)) "executable" else "file")
          val content = MessageDigest.getInstance("SHA-256")
          val stream = Files.newInputStream(file)
          try {
            Iterator.continually(stream.read(buffer)).takeWhile(_ != -1)
              .foreach(count => content.update(buffer, 0, count))
          } finally stream.close()
          digest.update(content.digest())
        } else throw new ResolutionException(s"Unsupported local input: $file")
      }
    }

    add("local-inputs-v1")
    visit(root)
    FilesSupport.hex(digest.digest())
  }
}
