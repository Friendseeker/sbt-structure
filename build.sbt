import java.io.File

import CrossVersion.partialVersion
import sbt.Keys.scalaVersion

def newProject(projectName: String): Project =
  Project(projectName, file(projectName))
    .settings(
      name := "sbt-structure-" + projectName,
      organization := "org.jetbrains",
      licenses += ("Apache-2.0", url(
        "http://www.apache.org/licenses/LICENSE-2.0.html"
      )),
      unmanagedSourceDirectories in Compile +=
        baseDirectory.value.getParentFile / "shared" / "src" / "main" / "scala",
      publishMavenStyle := false
    )
    .settings(
      bintrayRepository := "sbt-plugins",
      bintrayOrganization := Some("jetbrains"),
      bintrayVcsUrl := Some("https://github.com/jetbrains/sbt-structure")
    )

def xmlArtifact(scalaVersion: String) =
  partialVersion(scalaVersion) match {
    // if scala 2.11+ is used, add dependency on scala-xml module
    case Some((2, scalaMajor)) if scalaMajor >= 11 =>
      Seq("org.scala-lang.modules" %% "scala-xml" % "1.3.0")
    case _ =>
      Seq.empty
  }

lazy val core = newProject("core")
  .settings(
    libraryDependencies ++= xmlArtifact(scalaVersion.value),
    crossScalaVersions := Seq("2.10.7", "2.11.12", "2.12.11", "2.13.2")
  )

lazy val extractor = newProject("extractor")
  .settings(
    sbtPlugin := true,
    libraryDependencies ++= Seq(
      "com.googlecode.java-diff-utils" % "diffutils" % "1.2" % "test" withSources (),
      "org.specs2" %% "specs2-core" % "3.10.0" % "test",
      "org.specs2" %% "specs2-matcher-extra" % "3.10.0" % "test"
    ),
    // used only for testing, see publishVersions for versions that are actually used to publish artifacts
    crossSbtVersions := Seq("0.13.9", "0.13.13"),
    testSetup := {
      System.setProperty(
        "structure.sbtversion.full",
        (sbtVersion in pluginCrossBuild).value
      )
      System.setProperty(
        "structure.sbtversion.short",
        (sbtBinaryVersion in pluginCrossBuild).value
      )
      System.setProperty("structure.scalaversion", scalaBinaryVersion.value)
    },
    test in Test := (test in Test).dependsOn(testSetup).value,
    testOnly in Test := (testOnly in Test).dependsOn(testSetup).evaluated,
    name in bintray := "sbt-structure-extractor",
    scalacOptions ++= Seq("-deprecation"),
    sources in Compile := {
      val sbtVer = (sbtVersion in pluginCrossBuild).value
      val srcs = (sources in Compile).value
      // remove the AutoPlugin since it doesn't compile when testing for sbt 0.13.0
      // it's okay to compile it into the jar, old sbt won't know about it!
      if (sbtVer == "0.13.0")
        srcs.filterNot(_.getName == "StructurePlugin.scala")
      else srcs
    },
    // I want to share source between 0.13 and 1.0, but not 0.12
    unmanagedSourceDirectories in Compile ++= {
      val sbt013_100_shared = (sourceDirectory in Compile).value / "scala-sbt-0.13-1.0"
      partialVersion((sbtVersion in pluginCrossBuild).value) match {
        case Some((0, 13)) => Seq(sbt013_100_shared)
        case Some((1, _))  => Seq(sbt013_100_shared)
        case _             => Seq.empty[File]
      }
    }
  )
  .enablePlugins(TestDataDumper)

lazy val sbtStructure = project.in(file(".")).aggregate(core, extractor)

lazy val testSetup = taskKey[Unit]("Setup tests for extractor")

val publishSbtVersions = Seq("0.13.18", "1.3.10")
val publishAllCommand =
  "; reload ; project core ; + publish ; project extractor " +
    publishSbtVersions.map(v => s"; reload ; ^^ $v publish ").mkString
val publishAllLocalCommand =
  "; reload ; project core ; + publishLocal ; project extractor " +
    publishSbtVersions.map(v => s"; reload ; ^^ $v publishLocal ").mkString

// the ^ sbt-cross operator doesn't work that well for publishing, so we need to be more explicit about the command chain
addCommandAlias("publishAll", publishAllCommand)
addCommandAlias("publishAllLocal", publishAllLocalCommand)
