package chipyard.generator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.meta.{Source => ScalaTree, _}

private[generator] object SourceSelection {
  private val scalaSources = Paths.get("src/main/scala")
  private val identifier = "[A-Za-z_][A-Za-z0-9_]*"

  final case class Project(id: String, original: Path, staged: Path) {
    def source(path: Path): ScalaSource =
      ScalaSource(path, s"$id:${FilesSupport.portable(staged.relativize(path))}")
  }

  final case class ScalaSource(path: Path, label: String)

  final case class UnitSource(owner: Project, name: String, packageName: String, directory: Path) {
    val relativeDirectory: Path = Paths.get("integrations", name)
  }

  final case class Reason(source: String, line: Int, imported: String)

  final case class Plan(sources: Vector[String], units: Vector[(UnitSource, Option[Reason])]) {
    def selected: Vector[UnitSource] = units.collect { case (unit, Some(_)) => unit }

    def json: String = ResolutionFiles.obj(Vector(
      "activeScalaSources" -> ResolutionFiles.array(sources.map(ResolutionFiles.string)),
      "integrations" -> ResolutionFiles.array(units.map { case (unit, reason) =>
        ResolutionFiles.obj(Vector(
          "owner" -> ResolutionFiles.string(unit.owner.id),
          "unit" -> ResolutionFiles.string(FilesSupport.portable(unit.relativeDirectory)),
          "package" -> ResolutionFiles.string(unit.packageName),
          "selectedBy" -> reason.map { by =>
            ResolutionFiles.obj(Vector(
              "source" -> ResolutionFiles.string(by.source),
              "line" -> by.line.toString,
              "import" -> ResolutionFiles.string(by.imported)
            ))
          }.getOrElse("null")
        ))
      })
    ))
  }

  def entryPackage(source: ScalaSource, symbol: String, module: Boolean): String = {
    def definitions(stats: List[Stat], prefix: Vector[String]): Vector[Vector[String]] =
      stats.toVector.flatMap {
        case pkg: Pkg => definitions(pkg.body.stats, prefix ++ names(pkg.ref))
        case entry: Defn.Object if module && entry.name.value == symbol => Vector(prefix)
        case entry: Defn.Class if !module && entry.name.value == symbol => Vector(prefix)
        case _ => Vector.empty
      }

    definitions(parse(source).stats, Vector.empty) match {
      case Vector(path) if path.nonEmpty && path.forall(_.matches(identifier)) => path.mkString(".")
      case _ =>
        val kind = if (module) "object" else "class"
        throw new ResolutionException(s"${source.label} must define one top-level $kind $symbol in a named Scala package.")
    }
  }

  def moduleEntry(project: Project): ScalaSource = {
    val candidates = scalaFiles(project.staged.resolve(scalaSources)).filter(_.getFileName.toString == "Module.scala")
    candidates match {
      case Vector(path) => project.source(path)
      case _ => throw new ResolutionException(
        s"${project.id} must contain exactly one src/main/scala/**/Module.scala. Found ${candidates.size}.")
    }
  }

  /** Follows named imports in active Scala sources. Materializes integrations only when selected. */
  def plan(projects: Vector[Project], extraSources: Vector[ScalaSource],
      materialize: UnitSource => Unit): Plan = {
    val units = projects.flatMap(integrations).sortBy(unit => (unit.packageName, unit.owner.id))
    units.groupBy(_.packageName).toVector.sortBy(_._1).foreach { case (pkg, owners) =>
      if (owners.size > 1)
        throw new ResolutionException(s"Integration package '$pkg' is ambiguous: " +
          owners.map(_.owner.id).sorted.mkString(", "))
    }
    val bySpecificity = units.sortBy(unit => -unit.packageName.length)

    val initial = projects.flatMap(project => scalaFiles(project.staged.resolve(scalaSources)).map(project.source))
    val pending = mutable.Queue.empty[ScalaSource]
    pending ++= (initial ++ extraSources).sortBy(_.label)
    val selected = mutable.Map.empty[UnitSource, Reason]
    val active = Vector.newBuilder[String]
    val wildcards = Vector.newBuilder[(String, Reason)]

    while (pending.nonEmpty) {
      val source = pending.dequeue()
      active += source.label
      val imports = parse(source).collect { case imp: Import => imp }
      imports.foreach { imp =>
        imp.importers.foreach { importer =>
          val prefix = names(importer.ref).dropWhile(_ == "_root_")
          importer.importees.foreach { selector =>
            val reason = Reason(source.label, selector.pos.startLine + 1, imp.syntax)
            val name = selector match {
              case entry: Importee.Name => Some(entry.name.value)
              case entry: Importee.Rename => Some(entry.name.value)
              case _: Importee.Wildcard =>
                wildcards += ((prefix.mkString("."), reason))
                None
              case _ => None
            }
            name.foreach { imported =>
              val qualified = (prefix :+ imported).mkString(".")
              bySpecificity.find(unit => within(qualified, unit.packageName))
                .filterNot(selected.contains).foreach { unit =>
                  selected(unit) = reason
                  materialize(unit)
                  val staged = unit.owner.staged.resolve(unit.relativeDirectory).resolve(scalaSources)
                  pending ++= scalaFiles(staged).map(unit.owner.source)
                }
            }
          }
        }
      }
    }

    wildcards.result().foreach { case (prefix, reason) =>
      val optional = units.filter(unit =>
        within(prefix, unit.packageName) || (prefix.endsWith(".integrations") && within(unit.packageName, prefix)))
      if (optional.nonEmpty && !optional.exists(selected.contains))
        throw new ResolutionException(s"${reason.source}:${reason.line}: ${reason.imported} cannot select an " +
          "optional integration. Import a named package or symbol.")
    }

    Plan(active.result().distinct.sorted, units.map(unit => unit -> selected.get(unit)))
  }

  private def within(name: String, pkg: String): Boolean =
    name == pkg || name.startsWith(pkg + ".")

  private def integrations(project: Project): Vector[UnitSource] = {
    val root = project.original.resolve("integrations")
    if (!Files.isDirectory(root)) Vector.empty else {
      val children = Files.list(root)
      val dirs = try children.iterator().asScala.filter(path => Files.isDirectory(path.resolve(scalaSources)))
        .toVector.sortBy(_.getFileName.toString) finally children.close()
      if (dirs.isEmpty) Vector.empty else {
        val namespace = entryPackage(moduleEntry(project), "Module", module = true)
        dirs.map { path =>
          val name = path.getFileName.toString
          if (!name.matches(identifier))
            throw new ResolutionException(s"Integration directory $path must be a Scala package identifier.")
          UnitSource(project, name, s"$namespace.integrations.$name", path)
        }
      }
    }
  }

  def scalaFiles(root: Path): Vector[Path] =
    if (!Files.isDirectory(root)) Vector.empty else {
      val files = Files.walk(root)
      try files.iterator().asScala.filter(path => path.getFileName.toString.endsWith(".scala") && Files.isRegularFile(path))
        .toVector.sortBy(path => FilesSupport.portable(root.relativize(path)))
      finally files.close()
    }

  private def parse(source: ScalaSource): ScalaTree =
    dialects.Scala213(Input.VirtualFile(source.label, new String(Files.readAllBytes(source.path), UTF_8)))
      .parse[ScalaTree] match {
      case Parsed.Success(tree) => tree
      case failure: Parsed.Error => throw new ResolutionException(failure.toString)
    }

  private def names(ref: Term.Ref): Vector[String] = ref match {
    case name: Term.Name => Vector(name.value)
    case Term.Select(prefix: Term.Ref, name) => names(prefix) :+ name.value
    case _ => Vector.empty
  }
}
