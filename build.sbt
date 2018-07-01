
name:="scala-gopher"

organization:="com.github.rssh"

scalaVersion := "2.12.6"
crossScalaVersions := Seq("2.11.9", "2.12.6")

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

scalacOptions ++= Seq("-unchecked","-deprecation", "-feature" 
                         /* ,  "-Ymacro-debug-lite"  */
                         /*  ,   "-Ydebug"  ,  "-Ylog:lambdalift"  */ 
                     )

libraryDependencies += scalaVersion( "org.scala-lang" % "scala-reflect" % _ ).value

libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.6"
//libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.6-SNAPSHOT"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.13"

//testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-n", "Now")
fork in Test := true
//javaOptions in Test += s"""-javaagent:${System.getProperty("user.home")}/.ivy2/local/com.github.rssh/trackedfuture_2.11/0.3/jars/trackedfuture_2.11-assembly.jar"""

version:="0.99.11-SNAPSHOT"

publishMavenStyle := true

publishTo := version { (v: String) =>
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT")) 
    Some("snapshots" at nexus + "content/repositories/snapshots") 
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
} .value


publishArtifact in Test := false

pomIncludeRepository := { _ => false }

pomExtra := (
  <url>http://rssh.github.com/scala-gopher</url>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:rssh/scala-gopher.git</url>
    <connection>scm:git:git@github.com:rssh/scala-gopher.git</connection>
  </scm>
  <developers>
    <developer>
      <id>rssh</id>
      <name>Ruslan Shevchenko</name>
      <url>rssh.github.com</url>
    </developer>
  </developers>
)


