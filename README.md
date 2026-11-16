# http4s-servlet

Runs http4s apps as Java servlets.  Provides components for the [http4s-jetty][http4s-jetty] and [http4s-tomcat][http4s-tomcat]

[http4s-jetty]: https://github.com/http4s/http4s-jetty

## SBT coordinates

```scala
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-servlet" % "0.25.0-RC2"
)
```

## Compatibility

| http4s-servlet | http4s-core | servlet-api | Scala 2.12 | Scala 2.13 | Scala 3 | Status    |
|:---------------|:------------|:------------|------------|------------|---------|:----------|
| 0.23.x         | 0.23.x      | 3.1         | ✅         | ✅         | ✅      | Stable    |
| 0.24.x         | 0.23.x      | 4.0         | ✅         | ✅         | ✅      | Stable    |
| 0.25.x         | 0.23.x      | 5.0         | ✅         | ✅         | ✅      | RC        |
| 0.26.x         | 0.23.x      | 6.0         | ✅         | ✅         | ✅      | Milestone |
| 0.27.x         | 0.23.x      | 6.1         | ✅         | ✅         | ✅      | Milestone |

[http4s-jetty]: https://github.com/http4s/http4s-jetty/
[http4s-tomcat]: https://github.com/http4s/http4s-tomcat/


### Jetty 12 environments

Jetty 12 core is independent of the Servlet API. Servlet support is provided by an environment-specific module:

| Jetty environment | Platform       | Servlet API | Package namespace    |
|:------------------|:---------------|:------------|:---------------------|
| `ee8`             | Java EE 8      | 4.0         | `javax.servlet.*`    |
| `ee9`             | Jakarta EE 9   | 5.0         | `jakarta.servlet.*`  |
| `ee10`            | Jakarta EE 10  | 6.0         | `jakarta.servlet.*`  |
| `ee11`            | Jakarta EE 11  | 6.1         | `jakarta.servlet.*`  |

See Jetty's [environment compatibility table](https://github.com/jetty/jetty-examples#all-example-branches) and [web application deployment documentation](https://jetty.org/docs/jetty/12.1/operations-guide/deploy/index.html) for details.
