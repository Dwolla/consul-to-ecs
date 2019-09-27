lazy val buildSettings = Seq(
  scalaVersion := "2.12.10",
  organization := "com.dwolla",
  homepage := Some(url("https://github.com/Dwolla/consul-to-ecs")),
  description := "Utility to stop ECS tasks that are unhealthy in Consul",
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  startYear := Option(2019),
  libraryDependencies ++= {
    val fs2Version = "1.0.5"
    val awsJavaSdkVersion = "2.8.4"

    Seq(
      "software.amazon.awssdk" % "ecs" % awsJavaSdkVersion,
      "org.typelevel" %% "cats-core" % "2.0.0",
      "org.typelevel" %% "cats-effect" % "2.0.0",
      "com.comcast" %% "ip4s-cats" % "1.3.0",
      "co.fs2" %% "fs2-reactive-streams" % fs2Version,
      "io.getnelson.helm" %% "http4s" % "6.0.13",
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1",
      "com.chuusai" %% "shapeless" % "2.3.3",
//      "org.scalatest" %% "scalatest" % "3.0.8" % Test,
//      "com.dwolla" %% "testutils-scalatest-fs2" % "2.0.0-M3" % Test,
//      "com.ironcorelabs" %% "cats-scalatest" % "3.0.0" % Test,
//      "io.circe" %% "circe-literal" % circeVersion % Test,
    )
  },
)

lazy val `consul-to-ecs` = (project in file("."))
  .settings(buildSettings ++ noPublishSettings: _*)

lazy val noPublishSettings = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  Keys.`package` := file(""),
)

lazy val documentationSettings = Seq(
  autoAPIMappings := true,
  apiMappings ++= {
    // Lookup the path to jar (it's probably somewhere under ~/.ivy/cache) from computed classpath
    val classpath = (fullClasspath in Compile).value
    def findJar(name: String): File = {
      val regex = ("/" + name + "[^/]*.jar$").r
      classpath.find { jar => regex.findFirstIn(jar.data.toString).nonEmpty }.get.data // fail hard if not found
    }

    // Define external documentation paths
    Map(
      findJar("circe-generic-extra") -> url("http://circe.github.io/circe/api/io/circe/index.html"),
    )
  }
)
