enablePlugins(ChipyardPlugin)

name := "chipyard-selection-fixture"
chipyardVersion := "a7946ede9bd625204435715dc40c750e2f8a251f"
hardwareDependencies := Seq(Generator.local("fixture-dependency", baseDirectory.value / "dependency"))
