addCompilerPlugin("org.scalameta" % "semanticdb-scalac" % "4.1.12" cross CrossVersion.full)

def laraPublishSettings = Seq(
  bintrayOrganization := Some("epfl-lara"),
  bintrayRepository   := "princess",
  bintrayVcsUrl       := Some("git@github.com:epfl-lara/princess.git"),
  licenses            += ("LGPL-2.1", url("https://opensource.org/licenses/LGPL-2.1")),
)

lazy val commonSettings = laraPublishSettings ++ Seq(
    name                := "Princess",
    organization        := "uuverifiers",
    version             := "2019-06-26",
    scalaVersion        := "2.12.8",
    crossScalaVersions  := Seq("2.12.8", "2.13.0"),

    libraryDependencies += "org.scala-lang.modules" %% "scala-collection-compat" % "2.0.0",
    scalafixDependencies in ThisBuild += "org.scala-lang.modules" %% "scala-collection-migrations" % "2.0.0",
    scalacOptions ++= List("-Yrangepos", "-P:semanticdb:synthetics:on"),
)

// Jar files for the parsers

lazy val parserSettings = Seq(
    publishArtifact in packageDoc := true,
    publishArtifact in packageSrc := true,
    exportJars                    := true,
    crossPaths                    := true
)

lazy val parser = (project in file("parser")).
  settings(commonSettings: _*).
  settings(parserSettings: _*).
  settings(
    name := "Princess-parser",
    packageBin in Compile := baseDirectory.value / "parser.jar"
  ).
  disablePlugins(AssemblyPlugin)

lazy val smtParser = (project in file("smt-parser")).
  settings(commonSettings: _*).
  settings(parserSettings: _*).
  settings(
    name := "Princess-smt-parser",
    packageBin in Compile := baseDirectory.value / "smt-parser.jar"
  ).
  disablePlugins(AssemblyPlugin)

// Actual project

lazy val root = (project in file(".")).
  aggregate(parser, smtParser).
  dependsOn(parser, smtParser).
  settings(commonSettings: _*).

  settings(
    scalaSource in Compile := baseDirectory.value / "src",

    mainClass in Compile := Some("ap.CmdlMain"),

    scalacOptions in Compile ++= Seq(
      "-feature",
      "-language:implicitConversions",
      "-language:postfixOps",
      "-language:reflectiveCalls"
    ),

    scalacOptions += (scalaVersion map { sv => sv match {
      case "2.11.12" => "-optimise"
      case "2.12.8"  => "-opt:_"
      case "2.13.0"  => "-opt:_"
    }}).value,

    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
      "net.sf.squirrel-sql.thirdparty-non-maven" % "java-cup" % "0.11a",
    )
  )

