package org.jetbrains.sbt

import java.io.{File, PrintWriter}

import difflib._
import org.jetbrains.sbt.structure.XmlSerializer._
import org.jetbrains.sbt.structure._
import org.specs2.matcher._
import org.specs2.mutable._

import scala.collection.JavaConverters._
import scala.xml._
import scala.xml.transform.{RewriteRule, RuleTransformer}

class ImportSpec extends Specification with XmlMatchers with FileMatchers {

  // TODO make it possible to run each of the tests separately
  "Actual structure" should {
    sequential // running 10 sbt instances at once is a bad idea unless you have >16G of ram

    equalExpectedOneIn(
      "bare",
      options = "resolveClassifiers resolveSbtClassifiers",
      conditions = onlyFor("0.13.9", "0.13.13")
    )
    equalExpectedOneIn("dependency", onlyFor("0.13.9", "0.13.13"))
    equalExpectedOneIn("multiple", onlyFor("0.13.9", "0.13.13"))
    equalExpectedOneIn(
      "simple",
      options = "resolveClassifiers resolveSbtClassifiers",
      conditions = onlyFor("0.13.9", "0.13.13")
    )
    equalExpectedOneIn("classifiers", onlyFor("0.13.9", "0.13.13"))
    equalExpectedOneIn("optional", onlyFor("0.13.9", "0.13.13"))
    equalExpectedOneIn("play", onlyFor("0.13.9", "0.13.13"), options = "")
    equalExpectedOneIn("ide-settings", onlyFor("0.13.9", "0.13.13"))
    equalExpectedOneIn("sbt-idea", onlyFor("0.13.9", "0.13.13"))
    equalExpectedOneIn("custom-test-config", onlyFor("0.13.13"))

//    equalExpectedOneIn("android-1.4", onlyFor("0.13.9") and ifAndroidDefined)
//    equalExpectedOneIn("android", onlyFor("0.13.9") and ifAndroidDefined)
//    equalExpectedOneIn("android-1.6", onlyFor("0.13.13") and ifAndroidDefined)
  }

  private val SbtVersion = System.getProperty("structure.sbtversion.short")
  private val SbtVersionFull = System.getProperty("structure.sbtversion.full")
  private val ScalaVersion = System.getProperty("structure.scalaversion")

  private val PluginFile = new File(
    "extractor/target/scala-" + ScalaVersion + "/sbt-" + SbtVersion + "/classes/"
  ).getCanonicalFile

  private val sbtGlobalRoot = new File(
    System.getProperty("user.home"),
    ".sbt-structure-global/"
  ).getCanonicalFile
  private val sbtGlobalBase =
    new File(sbtGlobalRoot, SbtVersion).getCanonicalFile
  private val sbtBootDir = new File(sbtGlobalRoot, "boot/").getCanonicalFile
  private val sbtIvyHome = new File(sbtGlobalRoot, "ivy2/").getCanonicalFile

  private val TestDataRoot = new File("extractor/src/test/data/" + SbtVersion).getCanonicalFile
  private val AndroidHome =
    Option(System.getenv.get("ANDROID_HOME")).map(new File(_).getCanonicalFile)
  // assuming user.home is always defined
  private val UserHome = new File(System.getProperty("user.home")).getCanonicalFile

  private def equalExpectedOneIn(
    projectName: String,
    conditions: => MatchResult[Any] = always,
    options: String = "resolveClassifiers resolveSbtClassifiers resolveJavadocs"
  ) =
    ("equal expected one in '" + projectName + "' project [" + SbtVersionFull + "]")
      .in { _: String =>
        if (conditions.isSuccess)
          testProject(projectName, options)
        else
          conditions
      }

  private def testProject(project: String,
                          options: String): MatchResult[Elem] = {
    val base = new File(TestDataRoot, project)

    def structureFileName(suffix: String) =
      "structure-" + SbtVersionFull + suffix + ".xml"
    val testDataFile = new File(base, structureFileName(""))

    testDataFile must exist.setMessage(
      "No test data for version " + SbtVersionFull + " found at " + testDataFile.getPath
    )

    val expectedStr = getExpectedStr(testDataFile, base)
    val actualStr = Loader.load(
      base,
      options,
      SbtVersionFull,
      pluginFile = PluginFile,
      sbtGlobalBase = sbtGlobalBase,
      sbtBootDir = sbtBootDir,
      sbtIvyHome = sbtIvyHome
    )

    val actualXml = loadSanitizedXml(actualStr)
    val expectedXml = loadSanitizedXml(expectedStr)

    val actual = actualXml.deserialize[StructureData].right.get
    val expected = expectedXml.deserialize[StructureData].right.get

    def formatErrorMessage(message: String,
                           expected: String,
                           actual: String): String =
      String.format(
        "Project: %s %n%s %n%s",
        project,
        message,
        getDiff(expected, actual)
      )

    def onFail(): Unit = {
      dumpToFile(new File(base, structureFileName("-actual")), actualStr)
    }

    def onXmlFail: String = {
      onFail()
      val errorMessage = "Xml files are not equal, compare 'actual.xml' and 'structure-" + SbtVersionFull + ".xml'"
      formatErrorMessage(errorMessage, expectedStr, actualStr)
    }

    def onEqualsFail: String = {
      onFail()
      val actualPretty = prettyPrintCaseClass(actual)
      val expectedPretty = prettyPrintCaseClass(expected)
      dumpToFile(new File(base, "actual.txt"), actualPretty)
      dumpToFile(new File(base, "expected.txt"), expectedPretty)
      val errorMessage =
        "Objects are not equal, compare 'actual.txt' and 'expected.txt'"

      formatErrorMessage(errorMessage, expectedPretty, actualPretty)
    }

    (actual == expected) must beTrue.updateMessage(_ => onEqualsFail)
    actualXml must beEqualToIgnoringSpace(expectedXml).updateMessage(
      _ => onXmlFail
    )
  }

  private val xmlSanitizer = {
    val filterSettingValues = new RewriteRule {
      override def transform(n: Node): Seq[Node] = n match {
        case e: Elem if e.label == "setting" =>
          // setting values can change depending on where they are run, don't include in comparison
          e.copy(child = e.child.filterNot(_.label == "value"))
        case other => other
      }
    }
    new RuleTransformer(filterSettingValues)
  }

  /** Load and sanitize xml to exclude values that cause troubles when comparing in tests. */
  private def loadSanitizedXml(xmlString: String) = {
    try {
      val xml = XML.loadString(xmlString)
      val transformed = xmlSanitizer.transform(xml)
      xml.copy(child = NodeSeq.seqToNodeSeq(transformed).head.child)
    } catch {
      case x: Throwable =>
        throw new RuntimeException("failed to load and sanitize xml string", x)
    }
  }

  private def canon(path: String): String =
    path.stripSuffix("/").stripSuffix("\\")

  private def getExpectedStr(testDataFile: File, base: File): String = {
    val raw = TestUtil
      .read(testDataFile)
      .replace("$URI_BASE", base.getCanonicalFile.toURI.toString)
      .replace("$BASE", base.getCanonicalPath)
      .replace(
        "$URI_ANDROID_HOME",
        AndroidHome.map(p => canon(p.toURI.toString)).getOrElse("")
      )
      .replace(
        "$ANDROID_HOME",
        AndroidHome.map(p => canon(p.toString)).getOrElse("")
      )
      .replace("$IVY2", sbtIvyHome.getCanonicalPath)
      .replace("$SBT_BOOT", sbtBootDir.getCanonicalPath)
      .replace("$HOME", UserHome.getCanonicalPath)
    // re-serialize and deserialize again to normalize all system-dependent paths
    try {
      XML
        .loadString(raw)
        .deserialize[StructureData]
        .right
        .get
        .serialize
        .mkString
    } catch {
      case x: Exception =>
        throw new RuntimeException(
          "unable to read test data from " + testDataFile.getAbsolutePath,
          x
        )
    }
  }

  private def getDiff(expected: String, actual: String): String = {
    import scala.collection.JavaConversions._

    val result = new StringBuilder
    def appendToResult(str: Any): Unit =
      result.append(str + System.lineSeparator)

    val diff = DiffUtils.diff(expected.lines.toList, actual.lines.toList)
    diff.getDeltas foreach { delta =>
      appendToResult("Expected:")
      delta.getOriginal.getLines.asScala.foreach(appendToResult)
      appendToResult("Actual:")
      delta.getRevised.getLines.asScala.foreach(appendToResult)
      appendToResult("")
    }

    appendToResult("")
    result.toString
  }

  private def dumpToFile(file: File, contents: String): Unit = {
    val writer = new PrintWriter(file)
    writer.write(contents)
    writer.close()
  }

  private def prettyPrintCaseClass(toPrint: Product): String = {
    val indentStep = "  "
    def print0(what: Any, indent: String): String = what match {
      case p: Product =>
        if (p.productArity == 0) {
          indent + p.productPrefix
        } else {
          indent + p.productPrefix + ":\n" +
            p.productIterator
              .map {
                case s: Seq[_] =>
                  s.map(x => print0(x, indent + indentStep)).mkString("\n")
                case pp: Product => print0(pp, indent + indentStep)
                case other       => indent + indentStep + other.toString
              }
              .mkString("\n")
        }
      case other => indent + other.toString
    }

    print0(toPrint, indentStep)
  }

  private def onlyFor013 =
    SbtVersionFull must startWith("0.13")
      .orSkip(_ => "This test is only for SBT version 0.13.x")

  private def onlyFor(versions: String*): MatchResult[Seq[String]] =
    versions must contain[String](SbtVersionFull)
      .orSkip(_ => "This test is for SBT " + versions.mkString(", ") + " only")

  private def notFor(versions: String*) =
    versions must not(contain[String](SbtVersionFull)).orSkip(
      _ =>
        "This test is not applicable for SBT versions " + versions
          .mkString(", ")
    )

  private def ifAndroidDefined =
    AndroidHome must beSome.orSkip(_ => "ANDROID_HOME is not defined")

  private def always =
    true must beTrue
}
