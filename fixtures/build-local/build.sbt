enablePlugins(ChipyardPlugin)

name := "chipyard-local-build-fixture"
chipyardVersion := "a7946ede9bd625204435715dc40c750e2f8a251f"
hardwareDependencies := Seq(Generator.local("local-counter", baseDirectory.value / "generator"))
