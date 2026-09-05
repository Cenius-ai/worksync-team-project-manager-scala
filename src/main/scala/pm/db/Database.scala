package pm.db

import cats.effect.{IO, Resource}
import doobie.hikari.HikariTransactor
import doobie.util.transactor.Transactor
import pm.AppConfig

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

/** Connection pool for the embedded (default) or external database. */
object Database {
  private lazy val blockingEc: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool(r => {
      val t = new Thread(r, "worksync-jdbc")
      t.setDaemon(true)
      t
    }))

  def transactor(cfg: AppConfig): Resource[IO, Transactor[IO]] =
    HikariTransactor.newHikariTransactor[IO](
      cfg.jdbcDriver,
      cfg.dbUrl,
      cfg.dbUser,
      cfg.dbPass,
      blockingEc,
      None
    )
}
