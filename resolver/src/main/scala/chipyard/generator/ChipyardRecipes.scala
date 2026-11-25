package chipyard.generator

object ChipyardRecipes {
  private val prototype = PlatformRecipe(
    id = "chipyard-m1-v1",
    source = GitSource(
      "https://github.com/ucb-bar/chipyard.git",
      "a7946ede9bd625204435715dc40c750e2f8a251f"
    ),
    toolchain = Map(
      "jdk" -> "20",
      "sbt" -> "1.8.2",
      "scala" -> "2.13.16",
      "chisel" -> "6.7.0",
      "circt" -> "firtool-1.75.0",
      "verilator" -> "5.022"
    ),
    projects = Map(
      "boom" -> RecipeProject("generators/boom", Vector("rocket-chip", "pmu"), shared = false),
      "rocket-chip" -> RecipeProject("generators/rocket-chip", Vector("hardfloat", "diplomacy", "cde")),
      "hardfloat" -> RecipeProject("generators/hardfloat", Vector("firesim")),
      "diplomacy" -> RecipeProject("generators/diplomacy", Vector("cde")),
      "pmu" -> RecipeProject("generators/pmu", Vector("cde", "rocket-chip")),
      "cde" -> RecipeProject("tools/cde"),
      "firesim" -> RecipeProject("sims/firesim")
    )
  )

  def forCommit(commit: String): PlatformRecipe =
    if (commit.equalsIgnoreCase(prototype.source.ref)) prototype
    else throw new ResolutionException(
      s"No Chipyard recipe for '$commit'. The M1 prototype supports ${prototype.source.ref}."
    )
}
