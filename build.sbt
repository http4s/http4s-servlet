import sbtcrossproject.CrossProject

// https://typelevel.org/sbt-typelevel/faq.html#what-is-a-base-version-anyway
ThisBuild / tlBaseVersion := "0.0" // your current series x.y

ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("rossabaker", "Ross A. Baker")
)

// publish website from this branch
ThisBuild / tlSitePublishBranch := Some("main")

val Scala213 = "2.13.8"
ThisBuild / crossScalaVersions := Seq(Scala213, "2.12.15", "3.1.1")
ThisBuild / scalaVersion := Scala213 // the default Scala

val asyncHttpClientVersion = "2.12.3"
val catsEffectVersion = "3.3.9"
val disciplineMunitVersion = "1.0.9"
val fs2Version = "3.2.7"
val http4sVersion = "0.23.11"
val logbackVersion = "1.2.6"
val munitCatsEffectVersion = "1.0.7"
val scalacheckVersion = "1.15.4"
val scalacheckEffectVersion = "1.0.3"
val servletApiVersion = "3.1.0"
val jettyVersion = "9.4.46.v20220331"

lazy val root = tlCrossRootProject.aggregate(servlet, examples)

lazy val servlet = project
  .in(file("servlet"))
  .settings(
    name := "http4s-servlet",
    description := "Portable servlet implementation for http4s servers",
    startYear := Some(2013),
    libraryDependencies ++= Seq(
      "org.asynchttpclient" % "async-http-client" % asyncHttpClientVersion % Test,
      "org.eclipse.jetty" % "jetty-server" % jettyVersion % Test,
      "org.eclipse.jetty" % "jetty-servlet" % jettyVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.http4s" %% "http4s-server" % http4sVersion,
      "javax.servlet" % "javax.servlet-api" % servletApiVersion % Provided,
    ),
  )
  .dependsOn(testing.jvm % "test->test")

lazy val testing = CrossProject("testing", file("testing"))(JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "http4s-testing",
    description := "Internal utilities for http4s tests",
    startYear := Some(2016),
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion,
      "org.typelevel" %% "munit-cats-effect-3" % munitCatsEffectVersion,
      "org.typelevel" %% "discipline-munit" % disciplineMunitVersion,
      "org.scalacheck" %% "scalacheck" % scalacheckVersion,
      "org.typelevel" %% "scalacheck-effect" % scalacheckEffectVersion,
      "org.typelevel" %% "scalacheck-effect-munit" % scalacheckEffectVersion,
    ).map(_ % Test),
  )

lazy val examples = project
  .in(file("examples"))
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(JettyPlugin)
  .settings(
    name := "http4s-servlet-examples",
    description := "Examples for http4s-servlet",
    startYear := Some(2013),
    fork := true,
    Jetty / containerLibs := List("org.eclipse.jetty" % "jetty-runner" % jettyVersion),
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime,
      "javax.servlet" % "javax.servlet-api" % servletApiVersion % Provided,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-scala-xml" % http4sVersion,
    ),
  )
  .dependsOn(servlet)

lazy val docs = project.in(file("site")).enablePlugins(TypelevelSitePlugin)
