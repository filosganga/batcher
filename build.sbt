import org.typelevel.scalacoptions.ScalacOptions

val catsVersion = "2.11.0"

val catsEffectVersion = "3.6.3"

val fs2Version = "3.12.2"

val munitVersion = "1.0.0"

val munitCatsEffectVersion = "2.1.0"

val awsSdkVersion = "2.41.24"

val logbackVersion = "1.5.28"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion := "3.7.4"
ThisBuild / crossScalaVersions ++= List("3.3.7", "2.13.18")
ThisBuild / organization := "com.filippodeluca"
ThisBuild / organizationName := "Filippo De Luca"
ThisBuild / startYear := Some(2023)

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / developers := List(
  Developer(
    id = "filosganga",
    name = "Filippo De Luca",
    email = "me@filippodeluca.com",
    url = url("https://github.com/filosganga")
  )
)

ThisBuild / licenses := List(
  License.Apache2
)

ThisBuild / homepage := Some(
  url("https://github.com/filosganga/batcher")
)

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/filosganga/batcher"),
    "scm:git@github.com:filosganga/batcher.git"
  )
)

ThisBuild / dynverSonatypeSnapshots := true
ThisBuild / versionScheme := Some("semver-spec")
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle := true
ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / credentials ++= {
  for {
    usr <- sys.env.get("SONATYPE_USER")
    password <- sys.env.get("SONATYPE_PASS")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    "central.sonatype.com",
    usr,
    password
  )
}.toList

lazy val root = project
  .in(file("."))
  .aggregate(batcher.js, batcher.jvm, batcher.native, batcherIt)
  .settings(publish / skip := true)

lazy val batcher = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/batcher"))
  .settings(
    name := "batcher",
    tpolecatScalacOptions ++= Set(
      ScalacOptions.source3
    ),
    libraryDependencies ++= List(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion % Test,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % munitCatsEffectVersion % Test
    )
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := false
  )

lazy val batcherIt = project
  .in(file("modules/batcher-it"))
  .dependsOn(batcher.jvm)
  .settings(
    name := "batcher-it",
    tpolecatScalacOptions ++= Set(
      ScalacOptions.source3
    ),
    libraryDependencies ++= List(
      "org.scalameta" %%% "munit" % munitVersion % Test,
      "org.typelevel" %%% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "software.amazon.awssdk" % "dynamodb" % awsSdkVersion % Test,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Test
    ),
    publish / skip := true,
    Test / fork := true,
    Test / testForkedParallel := true,
    Test / testOptions += Tests.Setup(() => {
      sys.props.update(
        "software.amazon.awssdk.http.async.service.impl",
        "software.amazon.awssdk.http.nio.netty.NettySdkAsyncHttpService"
      )
    })
  )
