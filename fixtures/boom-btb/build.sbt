enablePlugins(ChipyardPlugin)

name := "boom-btb"
chipyardVersion := "a7946ede9bd625204435715dc40c750e2f8a251f"
hardwareDependencies := Seq(Generator.chipyard("boom"))
