enablePlugins(ChipyardPlugin)

name := "milestone-alias-boom"
chipyardVersion := "a7946ede9bd625204435715dc40c750e2f8a251f"

hardwareDependencies := {
  val repository = "https://github.com/riscv-boom/riscv-boom.git"
  val rocket = Generator.chipyard("rocket-chip")
  val pmu = Generator.chipyard("pmu")

  Seq(
    Generator.git("boom", repository, "58ef2720eae13be26b3008c02b5a74ce29c61c44", "boom_baseline")
      .requires(rocket),
    Generator.git("boom", repository, "ba03175e0a3cf5c0d5a7f14d385ac1c8998c4428", "boom_experimental")
      .requires(rocket, pmu)
  )
}
