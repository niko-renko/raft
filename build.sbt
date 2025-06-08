import com.typesafe.sbt.packager.docker.Cmd

val scala3Version = "3.6.4"
val AkkaVersion = "2.10.3"

enablePlugins(JavaAppPackaging, DockerPlugin)
resolvers += "Akka library repository".at("https://repo.akka.io/maven")

dockerBaseImage := "docker.io/library/eclipse-temurin:21-jre"
dockerCmd := Seq("3")
dockerCommands ++= Seq(
  Cmd("RUN", "chmod 777 /opt/docker/bin"),
  Cmd("WORKDIR", "/opt/docker/bin")
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "hw1",
    version := "0.1.0-SNAPSHOT",
    fork := true,
    run / connectInput := true,
    scalaVersion := scala3Version,
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.14"
    ),
    scalacOptions ++= Seq(
      "-Wunused:imports",
      "-Wunused:privates",
      "-Wunused:locals"
    )
  )
