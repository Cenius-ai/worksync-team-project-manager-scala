import sbt._

/** Real, released dependency versions resolved from Maven Central. */
object Dependencies {
  val http4sVersion = "0.23.30"
  val doobieVersion = "1.0.0-RC12"

  val http4sDsl        = "org.http4s"        %% "http4s-dsl"          % http4sVersion
  val http4sEmberServer = "org.http4s"       %% "http4s-ember-server" % http4sVersion
  val doobieCore       = "org.tpolecat"      %% "doobie-core"         % doobieVersion
  val doobieHikari     = "org.tpolecat"      %% "doobie-hikari"       % doobieVersion
  val h2               = "com.h2database"     % "h2"                  % "2.2.224"
  val postgres         = "org.postgresql"     % "postgresql"          % "42.7.5"
  val jbcrypt          = "org.mindrot"        % "jbcrypt"             % "0.4"
  val slf4jNop         = "org.slf4j"          % "slf4j-nop"           % "2.0.16"

  val all: Seq[ModuleID] = Seq(
    http4sDsl,
    http4sEmberServer,
    doobieCore,
    doobieHikari,
    h2,
    postgres,
    jbcrypt,
    slf4jNop
  )
}
