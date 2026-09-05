package pm

/** Runtime configuration. Every value has a safe, working default so a fresh
  * clone with no `.env` boots immediately. Env overrides are optional.
  */
final case class AppConfig(
    host: String,
    port: Int,
    dbUrl: String,
    dbUser: String,
    dbPass: String,
    jdbcDriver: String,
    sessionSecret: String,
    secureCookies: Boolean
)

object Config {
  /** Dev-only fallback, used only when SESSION_SECRET is absent. It is a
    * clearly-marked development value (never a real credential) and is the
    * SAME string at every read site so CSRF/session code always agrees.
    */
  val DevSessionSecret = "cenius-dev-9f1e4c7a3b2d5e80"

  private def env(key: String): Option[String] =
    sys.env.get(key).map(_.trim).filter(_.nonEmpty)

  def fromEnv: AppConfig = {
    val port   = env("PORT").flatMap(_.toIntOption).getOrElse(8080)
    val host   = env("HOST").getOrElse("0.0.0.0")
    val dbUrl  = env("DATABASE_URL").getOrElse("jdbc:h2:./data/worksync;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
    val isPg   = dbUrl.startsWith("jdbc:postgresql:")
    val dbUser = env("DB_USER").getOrElse(if (isPg) "postgres" else "sa")
    val dbPass = env("DB_PASSWORD").getOrElse(if (isPg) "postgres" else "")
    val driver = env("JDBC_DRIVER").getOrElse(if (isPg) "org.postgresql.Driver" else "org.h2.Driver")
    val secret = env("SESSION_SECRET").getOrElse(DevSessionSecret)
    AppConfig(
      host = host,
      port = port,
      dbUrl = dbUrl,
      dbUser = dbUser,
      dbPass = dbPass,
      jdbcDriver = driver,
      sessionSecret = secret,
      secureCookies = env("COOKIE_SECURE").exists(_ == "true")
    )
  }
}
