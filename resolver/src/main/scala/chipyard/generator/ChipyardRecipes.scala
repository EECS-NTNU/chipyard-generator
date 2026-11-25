package chipyard.generator

object ChipyardRecipes {
  private val prototype = PlatformRecipe(
    id = "chipyard-v2",
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
    ),
    buildSubmodules = Vector(
      "generators/bar-fetchers", "generators/boom", "generators/constellation",
      "generators/constellation/espresso", "generators/constellation/src/main/resources/csrc/netrace",
      "generators/diplomacy", "generators/hardfloat", "generators/hardfloat/berkeley-softfloat-3",
      "generators/hardfloat/berkeley-testfloat-3", "generators/icenet", "generators/pmu",
      "generators/rerocc", "generators/rocket-chip", "generators/rocket-chip-blocks",
      "generators/rocket-chip-inclusive-cache", "generators/shuttle", "generators/testchipip",
      "sims/firesim", "tools/DRAMSim2", "tools/cde", "tools/dsptools", "tools/firrtl2",
      "tools/fixedpoint", "tools/rocket-dsp-utils"
    )
  )

  def forCommit(commit: String): PlatformRecipe =
    if (commit.equalsIgnoreCase(prototype.source.ref)) prototype
    else throw new ResolutionException(
      s"No Chipyard recipe for '$commit'. The prototype supports ${prototype.source.ref}."
    )
}
