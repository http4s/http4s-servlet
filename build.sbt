// https://typelevel.org/sbt-typelevel/faq.html#what-is-a-base-version-anyway
ThisBuild / tlBaseVersion := "0.23" // your current series x.y
ThisBuild / tlMimaPreviousVersions ++= (0 to 11).map(y => s"0.23.$y").toSet
ThisBuild / tlMimaPreviousVersions := {
  scalaBinaryVersion.value match {
    case "2.12" =>
      (ThisBuild / tlMimaPreviousVersions).value - "0.23.12" // we missed this one... oops
    case _ => (ThisBuild / tlMimaPreviousVersions).value
  }
}

ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("rossabaker", "Ross A. Baker")
)

// publish website from this branch
ThisBuild / tlSitePublishBranch := Some("main")

val Scala213 = "2.13.8"
ThisBuild / crossScalaVersions := Seq("2.12.16", Scala213, "3.2.0")
ThisBuild / scalaVersion := Scala213 // the default Scala

lazy val root = tlCrossRootProject.aggregate(servlet, examples)

val asyncHttpClientVersion = "2.12.3"
val jettyVersion = "9.4.48.v20220622"
val http4sVersion = "0.23.14"
val munitCatsEffectVersion = "1.0.7"
val servletApiVersion = "3.1.0"

lazy val servlet = project
  .in(file("servlet"))
  .settings(
    name := "http4s-servlet",
    description := "Portable servlet implementation for http4s servers",
    startYear := Some(2013),
    libraryDependencies ++= Seq(
      "javax.servlet" % "javax.servlet-api" % servletApiVersion % Provided,
      "org.eclipse.jetty" % "jetty-client" % jettyVersion % Test,
      "org.eclipse.jetty" % "jetty-server" % jettyVersion % Test,
      "org.eclipse.jetty" % "jetty-servlet" % jettyVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.typelevel" %% "munit-cats-effect-3" % munitCatsEffectVersion % Test,
    ),
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
      "javax.servlet" % "javax.servlet-api" % servletApiVersion % Provided
    ),
  )
  .dependsOn(servlet)

lazy val docs = project.in(file("site")).enablePlugins(TypelevelSitePlugin)
