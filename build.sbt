import ReleaseTransformations._

sbtPlugin := true

scalaVersion := "2.10.4"

organization := "com.trueaccord.scalapb"

name := "sbt-scalapb"

addSbtPlugin("com.github.gseitz" % "sbt-protobuf" % "0.5.2")

releasePublishArtifactsAction := PgpKeys.publishSigned.value

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  ReleaseStep(action = Command.process("publishSigned", _), enableCrossBuild = true),
  setNextVersion,
  commitNextVersion,
  ReleaseStep(action = Command.process("sonatypeReleaseAll", _), enableCrossBuild = true),
  pushChanges
)

// This is the version of the scalaPb compiler and runtime going to be used.
// The version for the *plugin* is in version.sbt.
val scalaPbVersion = "0.5.32"

libraryDependencies ++= Seq(
  "com.trueaccord.scalapb" %% "compilerplugin" % scalaPbVersion
)

def genVersionFile(out: File, pluginVersion: String): Seq[File] = {
  out.mkdirs()
  val f = out / "Version.scala"
  val w = new java.io.FileOutputStream(f)
  w.write(s"""|// Generated by ScalaPB's build.sbt.
              |
              |package com.trueaccord.scalapb.plugin
              |
              |object Version {
              |  val pluginVersion = "$pluginVersion"
              |  val scalaPbVersion = "$scalaPbVersion"
              |}
              |""".stripMargin.getBytes("UTF-8"))
  w.close()
  Seq(f)
}

sourceGenerators in Compile <+= (sourceManaged in Compile, version in Compile) map {
  (sourceManaged, version) =>
    genVersionFile(sourceManaged, version)
}

ScriptedPlugin.scriptedSettings

scriptedBufferLog := false

scriptedLaunchOpts += s"-Dplugin.version=${version.value}"
