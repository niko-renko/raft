import com.typesafe.sbt.packager.docker.Cmd

val scala3Version = "3.6.4"
val AkkaVersion = "2.10.3"

enablePlugins(JavaAppPackaging, DockerPlugin)
resolvers += "Akka library repository".at("https://repo.akka.io/maven")

ThisBuild / dynverSeparator := "-"

dockerUpdateLatest := true
dockerBaseImage := "eclipse-temurin:17.0.15_6-jdk-jammy"
dockerCommands ++= Seq(
  Cmd("RUN", "chmod 777 /opt/docker/bin"),
  Cmd("WORKDIR", "/opt/docker/bin"),
  Cmd("USER", "root")
)
dockerRepository := Some(
  "northamerica-northeast1-docker.pkg.dev/main-461820/main"
)
// Docker / daemonUser := "root"

lazy val root = project
  .in(file("."))
  .settings(
    name := "raft",
    fork := true,
    run / connectInput := true,
    scalaVersion := scala3Version,
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-cluster-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.14"
    ),
    scalacOptions ++= Seq(
      "-Wunused:imports",
      "-Wunused:privates",
      "-Wunused:locals"
    )
  )
