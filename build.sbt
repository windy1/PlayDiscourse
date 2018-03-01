name := "play-discourse"
organization := "org.spongepowered"
version := "2.0.0-SNAPSHOT"

lazy val `playdiscourse` = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.4"
resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"
libraryDependencies ++= Seq(ws, specs2 % Test)

unmanagedResourceDirectories in Test +=  (baseDirectory.value / "target/web/public/test")
crossPaths := false

credentials += Credentials(
  sys.props.getOrElse("repo.name", "Sponge Repo"),
  sys.props.get("repo.url").map(new URL(_).getHost).getOrElse(""),
  sys.props.getOrElse("repo.user", ""),
  sys.props.getOrElse("repo.pwd", "")
)

publishTo := {
  val repoName = sys.props.get("repo.name")
  val repoUrl = sys.props.get("repo.url")
  if (repoName.isDefined && repoUrl.isDefined) {
    Some(repoName.get at repoUrl.get)
  } else {
    None
  }
}

// Replace default publish task with the one from sbt-aether-deploy
overridePublishSettings
