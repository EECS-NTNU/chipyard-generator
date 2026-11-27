ThisBuild / organization := "org.eecs-ntnu"
ThisBuild / version := "0.1.1"
ThisBuild / scalaVersion := "2.12.19"

lazy val root = project.in(file("."))
  .aggregate(resolver, plugin, cli)
  .settings(publish / skip := true)

lazy val resolver = project.settings(distPublishSettings).settings(
  name := "chipyard-generator-resolver",
  libraryDependencies += "org.scalameta" %% "scalameta" % "4.13.10"
)

lazy val plugin = project
  .enablePlugins(SbtPlugin)
  .dependsOn(resolver)
  .settings(distPublishSettings)
  .settings(name := "sbt-chipyard-generator")

lazy val stage = taskKey[File]("Stage the presolve launcher and its runtime libraries.")
lazy val dist = taskKey[File]("Build an installable Presolve release archive.")
lazy val distRepository = settingKey[File]("Staging repository for the Presolve distribution.")
lazy val distPublishConfiguration = taskKey[PublishConfiguration]("Configure distribution artifact publication.")
lazy val distPublish = taskKey[Unit]("Publish a module to the distribution staging repository.")
lazy val cleanDistRepository = taskKey[Unit]("Clear the distribution staging repository.")
lazy val packageDist = taskKey[File]("Package the staged distribution.")

lazy val distPublishSettings = Seq(
  otherResolvers += Resolver.file("presolve-dist", (LocalProject("cli") / distRepository).value)(Resolver.ivyStylePatterns),
  distPublishConfiguration := publishLocalConfiguration.value.withResolverName("presolve-dist"),
  distPublish := Classpaths.publishOrSkip(distPublishConfiguration, publishLocal / skip).value
)

lazy val cli: Project = project.settings(
  name := "presolve",
  publish / skip := true,
  distRepository := target.value / "dist-ivy",
  cleanDistRepository := IO.delete(distRepository.value),
  dist := Def.sequential(cleanDistRepository, packageDist).value,
  packageDist := {
    val staged = stage.value
    val repository = distRepository.value
    val installer = baseDirectory.value / "install.sh"
    val releaseVersion = version.value
    val release = s"presolve-$releaseVersion"
    val archive = target.value / s"$release.tar.gz"
    val log = streams.value.log
    (resolver / distPublish).value
    (plugin / distPublish).value
    IO.withTemporaryDirectory { temporary =>
      val bundle = temporary / release
      IO.copyDirectory(staged, bundle)
      IO.copyDirectory(repository, bundle / "ivy-local")
      IO.copyFile(installer, bundle / "install.sh")
      IO.write(bundle / "version", s"$releaseVersion\n")
      Seq(bundle / "presolve", bundle / "install.sh").foreach { executable =>
        if (!executable.setExecutable(true)) sys.error(s"Could not make $executable executable.")
      }
      val packaged = temporary / archive.getName
      val status = scala.sys.process.Process(Seq("tar", "-czf", packaged.getAbsolutePath, "-C",
        temporary.getAbsolutePath, release)).!
      if (status != 0) sys.error(s"Could not create $archive (tar exited with status $status).")
      IO.move(packaged, archive)
    }
    log.info(s"Packaged Presolve at $archive")
    archive
  },
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
