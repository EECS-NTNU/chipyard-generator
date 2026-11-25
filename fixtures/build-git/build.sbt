enablePlugins(ChipyardPlugin)

name := "chipyard-git-build-fixture"
chipyardVersion := "a7946ede9bd625204435715dc40c750e2f8a251f"

hardwareDependencies := {
  val fixture = baseDirectory.value / ".fixture"
  val pin = fixture / "revision"
  if (!pin.isFile) sys.error("Run ./prepare.sh to create the pinned Git generator.")
  Seq(Generator.git("git-counter", (fixture / "remote").toURI.toString, IO.read(pin).trim))
}
