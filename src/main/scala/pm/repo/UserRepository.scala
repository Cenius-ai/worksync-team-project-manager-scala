package pm.repo

import cats.effect.IO
import doobie.implicits._
import doobie.util.transactor.Transactor
import pm.domain.User

final class UserRepository(xa: Transactor[IO]) {
  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  def findByEmail(email: String): IO[Option[User]] =
    tx(sql"SELECT id, email, password_hash, display_name, role, active, created_at FROM users WHERE lower(email) = lower($email)".query[User].option)

  def findById(id: Long): IO[Option[User]] =
    tx(sql"SELECT id, email, password_hash, display_name, role, active, created_at FROM users WHERE id = $id".query[User].option)

  def insert(email: String, passwordHash: String, displayName: String, role: String, active: Boolean): IO[Long] =
    tx(sql"""INSERT INTO users (email, password_hash, display_name, role, active, created_at)
             VALUES ($email, $passwordHash, $displayName, $role, $active, ${pm.Util.nowMillis})"""
      .update.withUniqueGeneratedKeys[Long]("id"))

  def listAll: IO[List[User]] =
    tx(sql"SELECT id, email, password_hash, display_name, role, active, created_at FROM users ORDER BY created_at ASC".query[User].to[List])

  def updateRole(id: Long, role: String): IO[Int] =
    tx(sql"UPDATE users SET role = $role WHERE id = $id".update.run)

  def setActive(id: Long, active: Boolean): IO[Int] =
    tx(sql"UPDATE users SET active = $active WHERE id = $id".update.run)

  def count: IO[Long] =
    tx(sql"SELECT COUNT(*) FROM users".query[Long].unique)

  /** Users available as team members (never the disabled account). */
  def memberCandidates(excludeId: Option[Long] = None): IO[List[User]] = {
    val base = sql"SELECT id, email, password_hash, display_name, role, active, created_at FROM users WHERE active = TRUE"
    val q = excludeId match {
      case Some(id) => (base ++ fr"  AND id <> $id").query[User]
      case None     => base.query[User]
    }
    tx(q.to[List])
  }
}
