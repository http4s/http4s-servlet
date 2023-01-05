# http4s-servlet

Runs http4s apps as Java servlets.  Provides components for the [http4s-jetty][http4s-jetty] and [http4s-tomcat][http4s-tomcat]

[http4s-jetty]: https://github.com/http4s/http4s-jetty

## SBT coordinates

```scala
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-servlet" % http4sServletV
)
```

## Compatibility

| http4s-servlet | http4s-core | servlet-api | Scala 2.12 | Scala 2.13 | Scala 3 | Status    |
|:---------------|:------------|:------------|------------|------------|---------|:----------|
| 0.23.x         | 0.23.x      | 3.1         | ✅         | ✅         | ✅      | Stable    |
| 0.24.x         | 0.23.x      | 4.0         | ✅         | ✅         | ✅      | Milestone |
| 0.25.x         | 0.23.x      | 5.0         | ✅         | ✅         | ✅      | Milestone |

[http4s-jetty]: https://github.com/http4s/http4s-jetty/
[http4s-tomcat]: https://github.com/http4s/http4s-tomcat/
