ThisBuild / tlBaseVersion := "0.23" // your current series x.y

ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("rossabaker", "Ross A. Baker")
)

val Scala213 = "2.13.16"
ThisBuild / crossScalaVersions := Seq(Scala213, "3.3.6")
ThisBuild / scalaVersion := Scala213 // the default Scala
ThisBuild / startYear := Some(2013)

lazy val root = tlCrossRootProject.aggregate(servlet)

val asyncHttpClientVersion = "2.12.3"
val http4sVersion = "0.22.15"
val jettyVersion = "9.4.46.v20220331"
val servletVersion = "3.1.0"

lazy val servlet = project
  .in(file("servlet"))
  .settings(
    description := "Portable servlet implementation for http4s servers",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-server" % http4sVersion,
      "javax.servlet" % "javax.servlet-api" % servletVersion % Provided,
      "org.asynchttpclient" % "async-http-client" % asyncHttpClientVersion % Test,
      "org.eclipse.jetty" % "jetty-server" % jettyVersion % Test,
      "org.eclipse.jetty" % "jetty-servlet" % jettyVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
    ),
  )
  .dependsOn(testing % "test->test")

lazy val testing = project
  .in(file("testing"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-laws" % http4sVersion % Test
    )
  )
