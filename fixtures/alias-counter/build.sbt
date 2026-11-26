enablePlugins(ChipyardPlugin)

name := "alias-counter"
chipyardVersion := "a7946ede9bd625204435715dc40c750e2f8a251f"

hardwareDependencies := {
  val fixture = baseDirectory.value / ".fixture"
  val repository = (fixture / "remote").toURI.toString

  Seq(
    Generator.git("counter", repository, IO.read(fixture / "baseline").trim, "counter_baseline"),
    Generator.git("counter", repository, IO.read(fixture / "experimental").trim, "counter_experimental")
  )
}
