ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "pm.worksync"
ThisBuild / version := "1.0.0"

lazy val root = (project in file("."))
  .settings(
    name := "worksync",
    Compile / mainClass := Some("pm.Server"),
    libraryDependencies ++= Dependencies.all,
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    run / connectInput := false,
    run / outputStrategy := Some(StdoutOutput)
  )
