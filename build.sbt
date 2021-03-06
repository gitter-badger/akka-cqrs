import bintray.Keys._
import sbt.Keys._

lazy val commonSettings = Seq(
  organization := "com.productfoundry",
  version := "0.1.36",

  scalaVersion := "2.11.7",

  scalacOptions ++= Seq(
    "-encoding", "UTF-8",
    "-feature",
    "-unchecked",
    "-deprecation",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Yinline",
    "-Xfuture"
  ),

  // Bintray
  repository in bintray := "maven",
  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
  bintrayOrganization in bintray := Some("productfoundry"),

  // Test execution
  parallelExecution in Test := false,
  fork in Test := true,

  // Resolvers
  resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
)

lazy val akkaVersion = "2.4.1"

lazy val root = (project in file("."))
  .aggregate(inmem, core, cluster, test)
  .settings(commonSettings: _*)
  .settings(
    name := "akka-cqrs-root"
  )
  .settings(bintrayPublishSettings: _*)

lazy val inmem = project
  .settings(commonSettings: _*)
  .settings(
    name := "akka-cqrs-inmem",

    libraryDependencies ++= Seq(
      "com.typesafe.akka"      %% "akka-persistence"                    % akkaVersion,
      "org.scala-stm"          %% "scala-stm"                           % "0.7",
      "com.typesafe.akka"      %% "akka-persistence-tck"                % akkaVersion  % "test",
      "org.scalatest"          %% "scalatest"                           % "2.2.4"      % "test"
    )
  )
  .settings(bintrayPublishSettings: _*)

lazy val core = project
  .dependsOn(inmem)
  .settings(commonSettings: _*)
  .settings(
    name := "akka-cqrs",

    libraryDependencies ++= Seq(
      "com.typesafe.akka"      %% "akka-persistence"                    % akkaVersion,
      "com.google.protobuf"    %  "protobuf-java"                       % "2.5.0",
      "org.scala-stm"          %% "scala-stm"                           % "0.7",
      "org.scalatest"          %% "scalatest"                           % "2.2.4"     % "test",
      "com.typesafe.akka"      %% "akka-testkit"                        % akkaVersion % "test",
      "org.scalacheck"         %% "scalacheck"                          % "1.12.5"    % "test"
    )
  )
  .settings(bintrayPublishSettings: _*)

lazy val cluster = project
  .dependsOn(core, inmem)
  .settings(commonSettings: _*)
  .settings(
    name := "akka-cqrs-cluster",

    libraryDependencies ++= Seq(
      "com.typesafe.akka"      %% "akka-cluster"                        % akkaVersion,
      "com.typesafe.akka"      %% "akka-cluster-sharding"               % akkaVersion,
      "com.typesafe.akka"      %% "akka-cluster-tools"                  % akkaVersion
    )
  )
  .settings(bintrayPublishSettings: _*)

lazy val test = project
  .dependsOn(core)
  .settings(commonSettings: _*)
  .settings(
    name := "akka-cqrs-test",

    libraryDependencies ++= Seq(
      "org.scalatest"          %% "scalatest"                         % "2.2.4",
      "com.typesafe.akka"      %% "akka-testkit"                      % akkaVersion,
      "org.scalacheck"         %% "scalacheck"                        % "1.12.5"
    )
  )
  .settings(bintrayPublishSettings: _*)

