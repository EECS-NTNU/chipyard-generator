package chipyard.generator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

object ResolutionFiles {
  /** Writes the generated plan and replaces hardware.lock only on an explicit resolve. */
  def write(workspace: Path, target: Path, resolution: Resolution, updateLock: Boolean): Path = {
    val lock = workspace.resolve("hardware.lock")
    val locked = bytes(document(resolution, includeLocations = false))
    if (!updateLock && (!Files.isRegularFile(lock) || !java.util.Arrays.equals(Files.readAllBytes(lock), locked)))
      throw new ResolutionException(s"$lock is missing or out of date. Run hardwareResolve to update it.")

    val plan = bytes(document(resolution, includeLocations = true))
    val planPath = target.resolve("hardware").resolve(FilesSupport.sha256(plan)).resolve("plan.json")
    FilesSupport.writeAtomically(planPath, plan)
    if (updateLock) FilesSupport.writeAtomically(lock, locked)
    planPath
  }

  private def bytes(json: String): Array[Byte] = (json + "\n").getBytes(UTF_8)

  private def document(resolution: Resolution, includeLocations: Boolean): String = {
    def checkout(source: CachedSource): String = obj(
      Vector(
        "repository" -> string(source.repository),
        "commit" -> string(source.commit),
        "pins" -> array(source.pins.map { pin =>
          obj(Vector("path" -> string(pin.path), "repository" -> string(pin.repository), "commit" -> string(pin.commit)))
        })
      ) ++ (if (includeLocations) Vector("directory" -> string(source.directory.toString)) else Vector.empty)
    )

    def source(value: ResolvedSource): String = value match {
      case ResolvedLocal(path, fingerprint, directory) =>
        obj(Vector("kind" -> string("local"), "path" -> string(path), "fingerprint" -> string(fingerprint)) ++
          (if (includeLocations) Vector("directory" -> string(directory.toString)) else Vector.empty))
      case ResolvedGit(parent, submodules) =>
        obj(Vector(
          "kind" -> string("git"),
          "checkout" -> checkout(parent),
          "submodules" -> array(submodules.sortBy(_._1).map { case (path, child) =>
            obj(Vector("path" -> string(path), "checkout" -> checkout(child)))
          })
        ))
    }

    obj(Vector(
      "schemaVersion" -> "1",
      "platform" -> optional(resolution.platform.map(_.id)),
      "toolchain" -> obj(resolution.toolchain.toVector.sortBy(_._1).map { case (name, version) => name -> string(version) }),
      "roots" -> array(resolution.roots.sorted.map(string)),
      "nodes" -> array(resolution.nodes.sortBy(_.id).map { node =>
        obj(Vector(
          "id" -> string(node.id),
          "name" -> string(node.name),
          "alias" -> optional(node.alias),
          "recipe" -> optional(node.recipe),
          "dependencies" -> array(node.dependencies.sorted.map(string)),
          "source" -> source(node.source)
        ))
      })
    ))
  }

  private def optional(value: Option[String]): String = value.map(string).getOrElse("null")

  private def array(values: Vector[String]): String = block("[", "]", values)

  private def obj(fields: Vector[(String, String)]): String =
    block("{", "}", fields.map { case (key, value) => string(key) + ": " + value })

  private def block(open: String, close: String, entries: Vector[String]): String =
    if (entries.isEmpty) open + close
    else entries.map(entry => entry.split("\n", -1).map("  " + _).mkString("\n"))
      .mkString(open + "\n", ",\n", "\n" + close)

  private def string(value: String): String = {
    val escaped = value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case ch if ch < ' ' || Character.isSurrogate(ch) => f"\\u${ch.toInt}%04x"
      case ch => ch.toString
    }
    "\"" + escaped + "\""
  }
}
