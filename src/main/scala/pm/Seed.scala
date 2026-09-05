package pm

import cats.effect.{IO, IOApp}
import pm.db.Database
import pm.Util

/** Standalone seed/migrate entrypoint (no HTTP server). Runs migrations then
  * the idempotent demo seed and exits — used by install.sh and the SEED phase.
  */
object Seed extends IOApp.Simple {
  def run: IO[Unit] = {
    val cfg = Config.fromEnv
    for {
      _ <- IO(new java.io.File("data").mkdirs())
      _ <- Database.transactor(cfg).use { xa =>
        val env = new Env(cfg, xa)
        for {
          _ <- env.migrations.runAndLog()
          _ <- env.seeder.seedIfEmpty()
          _ <- env.seeder.logCounts()
          _ <- env.sessions.deleteExpired()
        } yield ()
      }
    } yield ()
  }
}
