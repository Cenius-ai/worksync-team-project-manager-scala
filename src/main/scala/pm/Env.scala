package pm

import doobie.util.transactor.Transactor
import pm.db.{Migrations, Seeder}
import pm.repo._

import cats.effect.IO

/** Shared application context — constructed once at boot. */
final class Env(val cfg: AppConfig, val xa: Transactor[IO]) {
  val users      = new UserRepository(xa)
  val sessions   = new SessionRepository(xa)
  val teams      = new TeamRepository(xa)
  val projects   = new ProjectRepository(xa)
  val tasks      = new TaskRepository(xa)
  val comments   = new CommentRepository(xa)
  val time       = new TimeEntryRepository(xa)
  val dashboard  = new DashboardRepository(xa)
  val migrations = new Migrations(xa)
  val seeder     = new Seeder(xa)
}
