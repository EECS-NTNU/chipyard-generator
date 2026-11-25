package chipyard.generator

import java.nio.file.Path

sealed trait Source
final case class LocalSource(path: Path) extends Source
final case class GitSource(url: String, ref: String) extends Source
final case class ChipyardSource(project: String) extends Source

final case class Generator(
    name: String,
    source: Source,
    alias: Option[String] = None,
    dependencies: Vector[Generator] = Vector.empty
) {
  def id: String = alias.getOrElse(name)

  def requires(dependencies: Generator*): Generator =
    copy(dependencies = this.dependencies ++ dependencies)
}

object Generator {
  def local(name: String, path: java.io.File, alias: String = ""): Generator =
    Generator(name, LocalSource(path.toPath), Option(alias).filter(_.nonEmpty))

  def git(name: String, url: String, ref: String, alias: String = ""): Generator =
    Generator(name, GitSource(url, ref), Option(alias).filter(_.nonEmpty))

  def chipyard(name: String, alias: String = ""): Generator =
    Generator(name, ChipyardSource(name), Option(alias).filter(_.nonEmpty))
}

final case class RecipeProject(
    submodule: String,
    dependencies: Vector[String] = Vector.empty,
    shared: Boolean = true
)

final case class PlatformRecipe(
    id: String,
    source: GitSource,
    toolchain: Map[String, String],
    projects: Map[String, RecipeProject]
)

final case class SubmodulePin(path: String, repository: String, commit: String)
final case class CachedSource(repository: String, commit: String, directory: Path, pins: Vector[SubmodulePin])

sealed trait ResolvedSource { def directory: Path }
final case class ResolvedLocal(path: String, fingerprint: String, directory: Path) extends ResolvedSource
final case class ResolvedGit(checkout: CachedSource, submodules: Vector[(String, CachedSource)]) extends ResolvedSource {
  def directory: Path = checkout.directory
}

final case class ResolvedNode(
    id: String,
    name: String,
    alias: Option[String],
    source: ResolvedSource,
    recipe: Option[String],
    dependencies: Vector[String]
)

final case class Resolution(
    roots: Vector[String],
    platform: Option[PlatformRecipe],
    toolchain: Map[String, String],
    nodes: Vector[ResolvedNode]
)

final class ResolutionException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
