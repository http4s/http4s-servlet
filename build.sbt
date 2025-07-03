ThisBuild / tlBaseVersion := "0.22" // your current series x.y

ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("rossabaker", "Ross A. Baker")
)

val Scala213 = "2.13.16"
ThisBuild / crossScalaVersions := Seq("2.12.20", Scala213, "3.3.6")
ThisBuild / scalaVersion := Scala213 // the default Scala

ThisBuild / githubWorkflowJavaVersions --= List(JavaSpec.temurin("8"))
ThisBuild / startYear := Some(2013)

lazy val root = tlCrossRootProject.aggregate(
  servlet4,
  servlet5,
  servlet6,
  examplesServlet4,
  examplesServlet5,
  examplesServlet6,
)

val asyncHttpClientVersion = "2.12.3"
val jettyVersion = "12.0.22"
val http4sVersion = "0.22.15"
val munitCatsEffectVersion = "1.0.7"
val servletApi6Version = "6.0.0"
val servletApi5Version = "5.0.0"
val servletApi4Version = "4.0.4"

val catsEffectVersion = "2.5.5"
val scalacheckVersion = "1.15.4"

val scalacheckEffectVersion = "1.0.3"

val Tomcat9Version = "9.0.106"
val Tomcat10Version = "10.0.27"
val Tomcat10_1Version = "10.1.42"

lazy val servlet4 = project
  .in(file("servlet4"))
  .settings(
    name := "http4s-servlet4",
    description := "Portable servlet implementation for http4s servers",
    tlJdkRelease := Some(11),
    Test / fork := true,
    libraryDependencies ++= Seq(
      "jakarta.servlet" % "jakarta.servlet-api" % servletApi4Version % Provided,
      "org.apache.tomcat.embed" % "tomcat-embed-core" % Tomcat9Version % Test,
      "org.apache.tomcat.embed" % "tomcat-embed-websocket" % Tomcat9Version % Test,
      "org.apache.tomcat" % "tomcat-catalina" % Tomcat9Version % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.typelevel" %% "munit-cats-effect-2" % munitCatsEffectVersion % Test,
      "org.asynchttpclient" % "async-http-client" % asyncHttpClientVersion % Test,
    ),
  )
  .dependsOn(testing % "test->test")

lazy val servlet5 = project
  .in(file("servlet5"))
  .settings(
    name := "http4s-servlet5",
    description := "Portable servlet implementation for http4s servers",
    tlJdkRelease := Some(11),
    Test / fork := true,
    libraryDependencies ++= Seq(
      "jakarta.servlet" % "jakarta.servlet-api" % servletApi6Version % Provided,
      "org.apache.tomcat.embed" % "tomcat-embed-core" % Tomcat10Version % Test,
      "org.apache.tomcat.embed" % "tomcat-embed-websocket" % Tomcat10Version % Test,
      "org.apache.tomcat" % "tomcat-catalina" % Tomcat10Version % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.typelevel" %% "munit-cats-effect-2" % munitCatsEffectVersion % Test,
      "org.asynchttpclient" % "async-http-client" % asyncHttpClientVersion % Test,
    ),
  )
  .dependsOn(testing % "test->test")

lazy val servlet6 = project
  .in(file("servlet6"))
  .settings(
    name := "http4s-servlet6",
    description := "Portable servlet implementation for http4s servers",
    tlJdkRelease := Some(11),
    Test / fork := true,
    libraryDependencies ++= Seq(
      "jakarta.servlet" % "jakarta.servlet-api" % servletApi6Version % Provided,
      "org.apache.tomcat.embed" % "tomcat-embed-core" % Tomcat10_1Version % Test,
      "org.apache.tomcat.embed" % "tomcat-embed-websocket" % Tomcat10_1Version % Test,
      "org.apache.tomcat" % "tomcat-catalina" % Tomcat10_1Version % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.typelevel" %% "munit-cats-effect-2" % munitCatsEffectVersion % Test,
      "org.asynchttpclient" % "async-http-client" % asyncHttpClientVersion % Test,
    ),
  )
  .dependsOn(testing % "test->test")

lazy val examplesServlet4 = project
  .in(file("examples-servlet4"))
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(JettyPlugin)
  .settings(
    name := "http4s-servlet-examples-servlet4",
    description := "Examples for http4s-servlet4",
    startYear := Some(2013),
    fork := true,
    Jetty / containerLibs := List("org.eclipse.jetty.ee8" % "jetty-ee8-runner" % jettyVersion),
    libraryDependencies ++= Seq(
      "jakarta.servlet" % "jakarta.servlet-api" % servletApi4Version % Provided
    ),
  )
  .dependsOn(servlet4)

lazy val examplesServlet5 = project
  .in(file("examples-servlet5"))
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(JettyPlugin)
  .settings(
    name := "http4s-servlet-examples-servlet5",
    description := "Examples for http4s-servlet5",
    startYear := Some(2013),
    fork := true,
    Jetty / containerLibs := List("org.eclipse.jetty.ee9" % "jetty-ee9-runner" % jettyVersion),
    libraryDependencies ++= Seq(
      "jakarta.servlet" % "jakarta.servlet-api" % servletApi5Version % Provided
    ),
  )
  .dependsOn(servlet5)

lazy val examplesServlet6 = project
  .in(file("examples-servlet6"))
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(JettyPlugin)
  .settings(
    githubWorkflowJavaVersions --= List(JavaSpec.temurin("8")),
    name := "http4s-servlet-examples-servlet6",
    description := "Examples for http4s-servlet6",
    startYear := Some(2013),
    fork := true,
    Jetty / containerLibs := List("org.eclipse.jetty.ee10" % "jetty-ee10-runner" % jettyVersion),
    libraryDependencies ++= Seq(
      "jakarta.servlet" % "jakarta.servlet-api" % servletApi6Version % Provided
    ),
  )
  .dependsOn(servlet6)

lazy val testing = project
  .in(file("testing"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "http4s-testing",
    description := "Internal utilities for http4s tests",
    startYear := Some(2016),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-laws" % http4sVersion % Test
    ),
  )
