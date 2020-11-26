
name:="scala-gopher"

organization:="com.github.rssh"

scalaVersion := "2.13.3"
//crossScalaVersions := Seq("2.12.7")

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/"

scalacOptions ++= Seq("-unchecked","-deprecation", "-feature", "-Xasync",
                         /* ,  "-Ymacro-debug-lite"  */
                         /*  ,   "-Ydebug"  ,  "-Ylog:lambdalift"  */ 
                     )

libraryDependencies += scalaVersion( "org.scala-lang" % "scala-reflect" % _ ).value

libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.10.0"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % "test"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.6.8"

//testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-n", "Now")
fork in Test := true
//javaOptions in Test += s"""-javaagent:${System.getProperty("user.home")}/.ivy2/local/com.github.rssh/trackedfuture_2.11/0.3/jars/trackedfuture_2.11-assembly.jar"""

version:="0.99.16-SNAPSHOT"

credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credentials")

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


