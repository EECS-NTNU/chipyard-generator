package chipyard.generator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.collection.mutable
import scala.meta._

private[generator] object AliasNamespace {
  def validateAlias(alias: String): Unit =
    dialects.Scala213(Input.String(alias)).parse[Term] match {
      case Parsed.Success(name: Term.Name)
          if name.value == alias && alias != "_root_" && alias.matches("[A-Za-z_][A-Za-z0-9_]*") =>
      case _ =>
        throw new ResolutionException(s"Dependency alias '$alias' must be a Scala package identifier.")
    }

  def root(sources: Vector[SourceSelection.ScalaSource], owner: String): String = {
    val roots = sources.flatMap { source =>
      SourceSelection.parse(source).stats.flatMap {
        case pkg: Pkg => Vector(first(pkg.ref).value)
        case pkg: Pkg.Object => Vector(pkg.name.value)
        case _: Import => Vector.empty
        case _ => throw new ResolutionException(s"${source.label} must declare a named package for dependency alias '$owner'.")
      }
    }.distinct.sorted
    roots match {
      case Vector(name) => name
      case _ =>
        val found = if (roots.isEmpty) "none" else roots.mkString(", ")
        throw new ResolutionException(s"Dependency alias '$owner' must own one top-level Scala package. Found $found.")
    }
  }

  /** Relocates package references in a writable source copy while preserving other text. */
  def relocate(source: SourceSelection.ScalaSource, from: String, to: String): Unit =
    if (from != to) {
      val tree = SourceSelection.parse(source)
      val edits = mutable.Set.empty[(Int, Int)]

      def replace(name: Name): Unit =
        if (name.value == from) edits += ((name.pos.start, name.pos.end))

      def reference(ref: Tree): Unit = ref match {
        case Term.Select(Term.Name("_root_"), name) => replace(name)
        case name: Term.Name if name.value == from && !ancestors(name).exists(shadows(_, name)) =>
          replace(name)
        case _ =>
      }

      tree.stats.foreach {
        case pkg: Pkg => replace(first(pkg.ref))
        case pkg: Pkg.Object => replace(pkg.name)
        case _ =>
      }
      tree.collect {
        case select: Term.Select if !inPackagePath(select) =>
          reference(select.qual)
        case select: Type.Select => reference(select.qual)
        case importer: Importer =>
          reference(importer.ref)
          importer.ref match {
            case Term.Name("_root_") =>
              importer.importees.foreach {
                case entry: Importee.Name => replace(entry.name)
                case entry: Importee.Rename => replace(entry.name)
                case entry: Importee.Unimport => replace(entry.name)
                case _ =>
              }
            case _ =>
          }
        case Mod.Private(name: Name) if packageAccess(name, from) => replace(name)
        case Mod.Protected(name: Name) if packageAccess(name, from) => replace(name)
      }
      if (edits.nonEmpty) {
        val text = new java.lang.StringBuilder(tree.pos.input.text)
        edits.toVector.sortBy(-_._1).foreach { case (start, end) => text.replace(start, end, to) }
        Files.write(source.path, text.toString.getBytes(UTF_8))
      }
    }

  private def first(ref: Term.Ref): Name = ref match {
    case name: Term.Name => name
    case Term.Select(prefix: Term.Ref, _) => first(prefix)
    case _ => throw new ResolutionException(s"Expected a named Scala package, found ${ref.syntax}.")
  }

  private def ancestors(tree: Tree): Iterator[Tree] =
    tree.parent.iterator.flatMap(parent => Iterator.single(parent) ++ ancestors(parent))

  private def inPackagePath(tree: Tree): Boolean = tree.parent.exists {
    case _: Pkg => true
    case select: Term.Select => inPackagePath(select)
    case _ => false
  }

  private def packageAccess(name: Name, from: String): Boolean =
    name.value == from && ancestors(name).collectFirst {
      case pkg: Pkg if packageNames(pkg.ref).contains(from) =>
        packageNames(pkg.ref).count(_ == from) == 1 && !ancestors(pkg).exists(_.isInstanceOf[Pkg])
      case pkg: Pkg.Object if pkg.name.value == from =>
        !ancestors(pkg).exists(_.isInstanceOf[Pkg])
      case member: Defn.Object if member.name.value == from => false
      case member: Defn.Class if member.name.value == from => false
      case member: Defn.Trait if member.name.value == from => false
    }.contains(true)

  private def packageNames(ref: Term.Ref): Vector[String] = ref match {
    case name: Term.Name => Vector(name.value)
    case Term.Select(prefix: Term.Ref, name) => packageNames(prefix) :+ name.value
    case _ => Vector.empty
  }

  private def shadows(scope: Tree, name: Name): Boolean = scope match {
    case body: Template.Body =>
      body.selfOpt.exists(_.name.value == name.value) || body.stats.exists(binds(_, name))
    case body: Tree.WithStats => body.stats.exists(binds(_, name))
    case clause: Case => patternBinds(clause.pat, name.value)
    case owner => owner.children.exists(parameterBinds(_, name.value))
  }

  private def binds(stat: Stat, name: Name): Boolean = stat match {
    case _: Pkg | _: Pkg.Object => false
    case declaration: Defn.Val => declaration.pats.exists(patternBinds(_, name.value))
    case declaration: Defn.Var => declaration.pats.exists(patternBinds(_, name.value))
    case declaration: Decl.Val => declaration.pats.exists(patternBinds(_, name.value))
    case declaration: Decl.Var => declaration.pats.exists(patternBinds(_, name.value))
    case declaration: Member => declaration.name.value == name.value
    case imp: Import if imp.pos.end <= name.pos.start =>
      imp.importers.exists { importer =>
        importer.importees.exists {
          case entry: Importee.Name => entry.name.value == name.value && importer.ref.syntax != "_root_"
          case entry: Importee.Rename => entry.rename.value == name.value
          case _ => false
        }
      }
    case _ => false
  }

  private def patternBinds(pattern: Pat, name: String): Boolean =
    pattern.collect { case Pat.Var(value) => value.value }.contains(name)

  private def parameterBinds(tree: Tree, name: String): Boolean = tree match {
    case parameter: Term.Param => parameter.name.value == name
    case parameter: Type.Param => parameter.name.value == name
    case clause: Member.ParamClauseGroup => clause.children.exists(parameterBinds(_, name))
    case clause: Term.ParamClause => clause.values.exists(parameterBinds(_, name))
    case clause: Type.ParamClause => clause.values.exists(parameterBinds(_, name))
    case constructor: Ctor.Primary => constructor.paramClauses.exists(parameterBinds(_, name))
    case _ => false
  }
}
