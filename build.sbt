// https://typelevel.org/sbt-typelevel/faq.html#what-is-a-base-version-anyway
ThisBuild / tlBaseVersion := "0.24" // your current series x.y

ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("rossabaker", "Ross A. Baker")
)

// publish website from this branch
ThisBuild / tlSitePublishBranch := Some("main")

val Scala213 = "2.13.18"
ThisBuild / crossScalaVersions := Seq("2.12.21", Scala213, "3.3.7")
ThisBuild / scalaVersion := Scala213 // the default Scala

// Undertow 2 for testing, requires Java 8 or higher
//ThisBuild / githubWorkflowJavaVersions -= JavaSpec.temurin("8")
ThisBuild / tlJdkRelease := Some(8)
ThisBuild / startYear := Some(2013)

lazy val root = tlCrossRootProject.aggregate(servlet, examples)

val asyncHttpClientVersion = "2.12.4"
val jettyVersion = "12.0.32"
val http4sVersion = "0.23.31"
val munitCatsEffectVersion = "2.1.0"
val servletApiVersion = "4.0.4"
val undertowVersion = "2.2.39.Final"

lazy val servlet = project
  .in(file("servlet"))
  .settings(
    name := "http4s-servlet",
    description := "Portable servlet implementation for http4s servers",
    fork := true,
    Test / javaOptions ++= Seq(
      "-Dcats.effect.trackFiberContext=true",
      "-Dcats.effect.tracing.mode=full",
      "-Dcats.effect.tracing.buffer.size=1024",
    ),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.13.0",
      "org.typelevel" %% "cats-effect" % "3.6.3",
      "jakarta.servlet" % "jakarta.servlet-api" % servletApiVersion % Provided,
      "io.undertow" % "undertow-core" % undertowVersion % Test,
      "io.undertow" % "undertow-servlet" % undertowVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "org.asynchttpclient" % "async-http-client" % asyncHttpClientVersion % Test,
    ),
  )

lazy val servletTesting = project
  .in(file("servlet-testing"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "http4s-servlet-testing",
    description := "Portable servlet implementation for http4s servers",
    Test / fork := true,
    libraryDependencies ++= Seq(
      "org.eclipse.jetty" % "jetty-client" % jettyVersion % Test,
      "org.eclipse.jetty" % "jetty-server" % jettyVersion % Test,
      "org.eclipse.jetty.ee8" % "jetty-ee8-servlet" % jettyVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
    ),
  )
  .dependsOn(servlet % "compile->compile;test->test")

lazy val examples = project
  .in(file("examples"))
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(JettyPlugin)
  .settings(
    name := "http4s-servlet-examples",
    description := "Examples for http4s-servlet",
    startYear := Some(2013),
    fork := true,
    Jetty / containerLibs := List("org.eclipse.jetty.ee8" % "jetty-ee8-runner" % jettyVersion),
    libraryDependencies ++= Seq(
      "jakarta.servlet" % "jakarta.servlet-api" % servletApiVersion % Provided
    ),
  )
  .dependsOn(servlet)

lazy val docs = project.in(file("site")).enablePlugins(Http4sOrgSitePlugin)
