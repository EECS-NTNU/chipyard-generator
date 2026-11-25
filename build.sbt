ThisBuild / organization := "org.eecs-ntnu"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.19"

lazy val root = project.in(file("."))
  .aggregate(resolver, plugin)
  .settings(publish / skip := true)

lazy val resolver = project.settings(
  name := "chipyard-generator-resolver"
)

lazy val plugin = project
  .enablePlugins(SbtPlugin)
  .dependsOn(resolver)
  .settings(name := "sbt-chipyard-generator")
