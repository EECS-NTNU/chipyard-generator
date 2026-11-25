enablePlugins(ChipyardPlugin)

name := "git-hardware-fixture"

hardwareDependencies := {
  val fixture = baseDirectory.value / ".fixture"
  val pins = fixture / "revisions"
  if (!pins.isFile) sys.error("Run ./prepare.sh to create the pinned Git fixture.")
  val revisions = IO.readLines(pins)
  val repository = (fixture / "remote").toURI.toString
  Seq(
    Generator.git("example-core", repository, revisions(0), alias = "baseline"),
    Generator.git("example-core", repository, revisions(1), alias = "experimental")
  )
}
