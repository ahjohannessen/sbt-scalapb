// Based on sbt-protobuf's Protobuf Plugin
// https://github.com/sbt/sbt-protobuf

package com.trueaccord.scalapb

import java.io.File

import sbt.Keys._
import sbt._
import sbtprotobuf.{ProtobufPlugin => PB}

object ScalaPbPlugin extends Plugin {
  // Set up aliases to SbtProtobuf tasks
  val includePaths = PB.includePaths
  val protoc = PB.protoc
  val externalIncludePath = PB.externalIncludePath
  val generatedTargets = PB.generatedTargets
  val generate = PB.generate
  val unpackDependencies = PB.unpackDependencies
  val protocOptions = PB.protocOptions
  val javaConversions = SettingKey[Boolean]("scalapb-java-conversions", "Generate Scala-Java protocol buffer conversions")
  val scalapbVersion =  SettingKey[String]("scalapb-version", "ScalaPB version.")

  val protobufConfig = PB.protobufConfig

  val pbScalaGenerate = TaskKey[Seq[File]]("protobuf-scala-generate", "Compile the protobuf sources.")

  val protobufSettings = PB.protobufSettings ++ inConfig(protobufConfig)(Seq[Setting[_]](
    pbScalaGenerate <<= sourceGeneratorTask.dependsOn(unpackDependencies),
    scalaSource <<= (sourceManaged in Compile) { _ / "compiled_protobuf" },

    javaConversions := false,
    scalapbVersion := com.trueaccord.scalapb.plugin.Version.scalaPbVersion,
    generatedTargets <<= (javaConversions in PB.protobufConfig,
      javaSource in PB.protobufConfig, scalaSource in PB.protobufConfig) {
      (javaConversions, javaSource, scalaSource) =>
        (scalaSource, "*.scala") +:
          (if (javaConversions)
            Seq((javaSource, "*.java"))
          else
            Nil)
    },
    version := "2.6.1",

    protocOptions <++= (generatedTargets in protobufConfig, javaConversions in protobufConfig) {
      (generatedTargets, javaConversions) =>
      generatedTargets.find(_._2.endsWith(".scala")) match {
        case Some(targetForScala) =>
          val params = if (javaConversions) "java_conversions" else ""
          Seq(s"--scala_out=$params:${targetForScala._1.absolutePath}")
        case None => Nil
      }
    })) ++ Seq[Setting[_]](
    libraryDependencies <++= (scalapbVersion in protobufConfig) {
      runtimeVersion =>
        Seq(
          "com.trueaccord.scalapb" %% "scalapb-runtime" % runtimeVersion
        )
    },
    (sourceGenerators in Compile) <<= (sourceGenerators in Compile, generate.in(protobufConfig),
      pbScalaGenerate.in(protobufConfig)) {
      case (srcGens, originalCompile, pbScalaGenerate) => srcGens.map {
        case task if task == originalCompile => pbScalaGenerate
        case e => e
      }
    })

  private def executeProtoc(protocCommand: String, schemas: Set[File], includePaths: Seq[File], protocOptions: Seq[String], log: Logger) = try {
    com.trueaccord.scalapb.compiler.Process.runProtocUsing(
      protocCommand, schemas.map(_.absolutePath).toSeq, includePaths.map(_.absolutePath), protocOptions)(
        l => Process(l.head, l.tail) ! log)
  } catch {
    case e: Exception =>
      throw new RuntimeException("error occured while compiling protobuf files: %s" format (e.getMessage), e)
  }

  private def compile(protocCommand: String, schemas: Set[File], includePaths: Seq[File], protocOptions: Seq[String], generatedTargets: Seq[(File, String)], log: Logger) = {
    val generatedTargetDirs = generatedTargets.map(_._1)

    generatedTargetDirs.foreach(_.mkdirs())

    log.info("Compiling %d protobuf files to %s".format(schemas.size, generatedTargetDirs.mkString(",")))
    log.debug("protoc options:")
    protocOptions.map("\t" + _).foreach(log.debug(_))
    schemas.foreach(schema => log.info("Compiling schema %s" format schema))

    val exitCode = executeProtoc(protocCommand, schemas, includePaths, protocOptions, log)
    if (exitCode != 0)
      sys.error("protoc returned exit code: %d" format exitCode)

    log.info("Compiling protobuf")
    generatedTargetDirs.foreach { dir =>
      log.info("Protoc target directory: %s".format(dir.absolutePath))
    }

    (generatedTargets.flatMap { ot => (ot._1 ** ot._2).get}).toSet
  }

  private def sourceGeneratorTask =
    (streams, sourceDirectories in protobufConfig, includePaths in protobufConfig, protocOptions in protobufConfig, generatedTargets in protobufConfig, protoc) map {
      (out, srcDirs, includePaths, protocOpts, otherTargets, protocCommand) =>
        val schemas = srcDirs.toSet[File].flatMap(srcDir => (srcDir ** "*.proto").get.map(_.getAbsoluteFile))
        val cachedCompile = FileFunction.cached(out.cacheDirectory / "protobuf", inStyle = FilesInfo.lastModified, outStyle = FilesInfo.exists) { (in: Set[File]) =>
          compile(protocCommand, schemas, includePaths, protocOpts, otherTargets, out.log)
        }
        cachedCompile(schemas).toSeq
    }
}
