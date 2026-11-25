ThisBuild / organization := "org.eecs-ntnu"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.19"

lazy val root = project.in(file("."))
  .aggregate(resolver, plugin, cli)
  .settings(publish / skip := true)

lazy val resolver = project.settings(
  name := "chipyard-generator-resolver",
  libraryDependencies += "org.scalameta" %% "scalameta" % "4.13.10"
)

lazy val plugin = project
  .enablePlugins(SbtPlugin)
  .dependsOn(resolver)
  .settings(name := "sbt-chipyard-generator")

lazy val stage = taskKey[File]("Stage the presolve launcher and its runtime libraries.")

lazy val cli = project.settings(
  name := "presolve",
  publish / skip := true,
  Compile / resourceGenerators += Def.task {
    val metadata = (Compile / resourceManaged).value / "presolve.properties"
    IO.write(metadata, Seq(
      s"organization=${(plugin / organization).value}",
      s"plugin=${(plugin / moduleName).value}",
      s"version=${(plugin / version).value}",
      s"sbt=${sbtVersion.value}"
    ).mkString("", "\n", "\n"))
    Seq(metadata)
  }.taskValue,
  stage := {
    val directory = target.value / "stage"
    val launcher = directory / "presolve"
    val source = baseDirectory.value / "presolve"
    val jars = (Runtime / externalDependencyClasspath).value.map(_.data) :+ (Compile / packageBin).value
    IO.delete(directory)
    IO.copyFile(source, launcher)
    if (!launcher.setExecutable(true)) sys.error(s"Could not make $launcher executable.")
    jars.foreach(jar => IO.copyFile(jar, directory / "lib" / jar.getName))
    streams.value.log.info(s"Staged presolve at $launcher")
    directory
  }
)
