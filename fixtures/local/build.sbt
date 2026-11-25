enablePlugins(ChipyardPlugin)

name := "local-hardware-fixture"

hardwareDependencies := Seq(Generator.local("custom-cache", baseDirectory.value / "generator"))
