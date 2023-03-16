val catsVersion = "2.9.0"
val catsEffectVersion = "3.4.8"
val fs2Version = "3.6.1"
val munitVersion = "1.0.0-M7"
val munitCatsEffectVersion = "2.0.0-M3"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion := "3.2.0"
ThisBuild / crossScalaVersions ++= List("2.13.10", "2.12.17")
ThisBuild / organization := "com.filippodeluca"
ThisBuild / organizationName := "Filippo De Luca"

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val root = project
  .in(file("."))
  .aggregate(batcher.js, batcher.jvm, batcher.native)
  .settings(
    publish := {},
    publishLocal := {}
  )

lazy val batcher = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/batcher"))
  .settings(
    name := "batcher",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions += "-Xsource:3",
    libraryDependencies ++= List(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % munitCatsEffectVersion % Test
    )
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := false
  )
