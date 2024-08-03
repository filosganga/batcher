val catsVersion = "2.10.0"

val catsEffectVersion = "3.5.4"

val fs2Version = "3.10.2"

val munitVersion = "1.0.0"

val munitCatsEffectVersion = "2.0.0"

val awsSdkVersion = "2.26.29"

val logbackVersion = "1.5.6"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion := "3.3.3"
ThisBuild / crossScalaVersions ++= List("2.13.13", "2.12.19")
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
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
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

val sonatypeSettings = List(
  // Setting it on ThisBuild does not have any effect
  sonatypePublishToBundle := {
    if (isSnapshot.value) {
      Some(sonatypeSnapshotResolver.value)
    } else {
      Some(Resolver.file("sonatype-local-bundle", sonatypeBundleDirectory.value))
    }
  }
)

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
    sonatypeSettings,
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
