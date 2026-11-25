enablePlugins(ChipyardPlugin)

name := "milestone-wrapper-consumer"
chipyardVersion := "a7946ede9bd625204435715dc40c750e2f8a251f"

hardwareDependencies := Seq(
  Generator.git("rtl-fixture", (baseDirectory.value / ".." / "verilog-wrapper").getCanonicalFile.toURI.toString,
    IO.read(baseDirectory.value / "wrapper.commit").trim)
)
