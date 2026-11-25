package chipyard.generator

import java.nio.file.Path
import scala.collection.mutable

/** Resolves evaluated declarations independently of sbt and without loading dependency builds. */
final class Resolver(cache: GitCache) {
  import Resolver._

  def resolve(
      workspace: Path,
      declarations: Seq[Generator],
      platform: Option[PlatformRecipe] = None,
      toolchain: Map[String, String] = Map.empty
  ): Resolution = {
    val rootDirectory = workspace.toRealPath()
    val roots = declarations.toVector ++ platform.toVector.map(_ => Generator.chipyard(platformId))
    roots.foreach(validate)
    roots.groupBy(_.id).toVector.sortBy(_._1).collectFirst {
      case (id, instances) if instances.size > 1 => id
    }.foreach(id => throw new ResolutionException(s"Duplicate dependency alias '$id' in workspace declarations."))

    val platformTools = platform.map(_.toolchain).getOrElse(Map.empty)
    (platformTools.keySet intersect toolchain.keySet).toVector.sorted.foreach { tool =>
      if (platformTools(tool) != toolchain(tool))
        throw new ResolutionException(s"Toolchain conflict for $tool: workspace requires ${toolchain(tool)}, " +
          s"but ${platform.get.id} requires ${platformTools(tool)}.")
    }
    val requirements = mutable.Map.empty[String, Requirement]
    val visited = mutable.Set.empty[Generator]
    val active = mutable.Set.empty[String]
    val sharedNames = platform.toVector.flatMap(_.projects.collect {
      case (name, project) if project.shared => name
    }).toSet ++ platform.map(_ => platformId)

    def visit(generator: Generator, parents: Vector[String]): Unit = {
      validate(generator)
      val path = parents :+ generator.id
      val id = generator.id
      if (active(id))
        throw new ResolutionException(s"Dependency cycle: ${path.mkString(" -> ")}")
      if (sharedNames(generator.name) && id != generator.name)
        throw new ResolutionException(s"Shared platform dependency '${generator.name}' cannot use alias '$id' " +
          s"(${path.mkString(" -> ")}).")

      val normalized = generator.copy(source = generator.source match {
        case GitSource(url, ref) => GitSource(GitCache.canonicalRepository(url), GitCache.pinnedCommit(ref))
        case LocalSource(path) => LocalSource(rootDirectory.resolve(path).normalize())
        case source => source
      })
      val (recipe, recipeDependencies) = generator.source match {
        case ChipyardSource(project) =>
          val selected = platform.getOrElse(throw new ResolutionException(
            s"${path.mkString(" -> ")} requires a pinned chipyardVersion."))
          if (project == platformId) (Some(selected.id), Vector.empty)
          else {
            val definition = selected.projects.getOrElse(project, throw new ResolutionException(
              s"Recipe ${selected.id} has no Chipyard generator '$project'."))
            (Some(s"${selected.id}:$project"), Vector(Generator.chipyard(platformId)) ++
              definition.dependencies.map(name => Generator.chipyard(name)))
          }
        case _ => (None, Vector.empty)
      }

      val dependencies = normalized.dependencies ++ recipeDependencies
      val edges = dependencies.map(_.id).distinct.sorted
      val requirement = Requirement(normalized, recipe, edges, path)
      requirements.get(id).foreach { previous =>
        if (previous.generator.name != normalized.name || previous.generator.source != normalized.source ||
            previous.edges != edges)
          throw new ResolutionException(s"Incompatible requirements for '$id':\n" +
            s"  ${previous.describe}\n  ${requirement.describe}")
      }
      requirements.getOrElseUpdate(id, requirement)
      if (visited.add(normalized)) {
        active += id
        dependencies.sortBy(_.id).foreach(visit(_, path))
        active -= id
      }
    }

    roots.sortBy(_.id).foreach(visit(_, Vector.empty))
    lazy val platformCheckout = cache.get(platform.get.source)
    val nodes = requirements.toVector.sortBy(_._1).map { case (id, requirement) =>
      val generator = requirement.generator
      val source = generator.source match {
        case LocalSource(local) => LocalInputs.resolve(rootDirectory, local)
        case git: GitSource => cache.withSubmodules(cache.get(git))
        case ChipyardSource(`platformId`) => ResolvedGit(platformCheckout, Vector.empty)
        case ChipyardSource(project) =>
          val selected = platform.get
          val submodulePath = selected.projects(project).submodule
          val pin = platformCheckout.pins.find(_.path == submodulePath).getOrElse(throw new ResolutionException(
            s"Recipe ${selected.id} expected the submodule '$submodulePath' at ${platformCheckout.commit}."))
          ResolvedGit(cache.getSubmodule(platformCheckout, pin), Vector.empty)
      }
      ResolvedNode(id, generator.name, generator.alias, source, requirement.recipe, requirement.edges)
    }
    Resolution(roots.map(_.id).sorted, platform, platformTools ++ toolchain, nodes)
  }
}

object Resolver {
  private val platformId = "chipyard"

  private final case class Requirement(generator: Generator, recipe: Option[String], edges: Vector[String],
      path: Vector[String]) {
    def describe: String =
      s"${path.mkString(" -> ")} requires ${generator.name} from ${generator.source} with dependencies " +
        edges.mkString("[", ", ", "]")
  }

  private def validate(generator: Generator): Unit =
    (generator.name +: generator.alias.toVector).foreach { name =>
      if (!name.matches("[a-zA-Z_][a-zA-Z0-9_-]*"))
        throw new ResolutionException(s"Invalid dependency name or alias '$name'. Use letters, digits, '_' or '-'.")
    }
}
