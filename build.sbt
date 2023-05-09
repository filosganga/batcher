val catsVersion = "2.9.0"

val catsEffectVersion = "3.4.10"

val fs2Version = "3.6.1"

val munitVersion = "1.0.0-M7"

val munitCatsEffectVersion = "2.0.0-M3"

val awsSdkVersion = "2.20.62"

val logbackVersion = "1.4.7"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion := "3.2.2"
ThisBuild / crossScalaVersions ++= List("2.13.10", "2.12.17")
ThisBuild / organization := "com.filippodeluca"
ThisBuild / organizationName := "Filippo De Luca"
ThisBuild / startYear := Some(2023)

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / dynverSonatypeSnapshots := true

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

ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true
ThisBuild / credentials ++= {
  for {
    usr <- sys.env.get("SONATYPE_USER")
    password <- sys.env.get("SONATYPE_PASS")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    "s01.oss.sonatype.org",
    usr,
    password
  )
}.toList
ThisBuild / versionScheme := Some("semver-spec")

lazy val root = project
  .in(file("."))
  .aggregate(batcher.js, batcher.jvm, batcher.native)
  .settings(publish / skip := true)

lazy val batcher = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/batcher"))
  .settings(
    name := "batcher",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, _)) => List("-Xsource:3")
        case _ => List.empty
      }
    },
    libraryDependencies ++= List(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion % Test,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "org.scalameta" %%% "munit" % munitVersion % s"$Test;$IntegrationTest",
      "org.typelevel" %%% "munit-cats-effect" % munitCatsEffectVersion % s"$Test;$IntegrationTest"
    )
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := false
  )
  .jsSettings(inConfig(IntegrationTest)(ScalaJSPlugin.testConfigSettings): _*)
  // add the `shared` folder to source directories
  .settings(
    IntegrationTest / unmanagedSourceDirectories ++=
      CrossType.Full.sharedSrcDir(baseDirectory.value, IntegrationTest.name).toSeq
  )
  .configs(IntegrationTest)
  .settings(inConfig(IntegrationTest)(Defaults.testSettings))
  .jvmSettings(
    IntegrationTest / testOptions += Tests.Setup(() => {
      sys.props.update("logback.configurationFile", "logback-it.xml")
      sys.props.update(
        "software.amazon.awssdk.http.async.service.impl",
        "software.amazon.awssdk.http.nio.netty.NettySdkAsyncHttpService"
      )
    }),
    libraryDependencies ++= List(
      "software.amazon.awssdk" % "dynamodb" % awsSdkVersion % IntegrationTest,
      "ch.qos.logback" % "logback-classic" % logbackVersion % IntegrationTest
    )
  )
