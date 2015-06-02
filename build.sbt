name := "rsd"

scalaVersion := "2.11.6"

libraryDependencies ++= List(
  "com.typesafe.slick" %% "slick" % "3.0.0",
  "ch.qos.logback" % "logback-classic" % "1.1.2" % Runtime,
  "com.zaxxer" % "HikariCP" % "2.3.6",
  "mysql" % "mysql-connector-java" % "5.1.32",
  "com.h2database" % "h2" % "1.3.175",
  "org.postgresql" % "postgresql" % "9.3-1102-jdbc41",
  "com.typesafe.akka" % "akka-stream-experimental_2.11" % "1.0-M5"
)
