package pm.repo

import cats.effect.IO
import cats.syntax.all.*
import doobie.implicits._
import doobie.util.transactor.Transactor
import pm.Util
import pm.domain.{Session, User}

import java.util.UUID

final class SessionRepository(xa: Transactor[IO]) {
  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  private val SessionDurationMs = 30L * 24 * 3600 * 1000 // 30 days

  /** Create a fresh session row and return its token. */
  def create(userId: Long): IO[String] = {
    val token = UUID.randomUUID().toString
    val nowMs = Util.nowMillis
    tx(sql"""INSERT INTO sessions (token, user_id, created_at, expires_at)
             VALUES ($token, $userId, $nowMs, ${nowMs + SessionDurationMs})""".update.run)
      .as(token)
  }

  /** Resolve a token to its (user, session) when valid and the user is active.
    * One read — every protected route relies on this.
    */
  def findValidUser(token: String): IO[Option[(User, Session)]] =
    tx {
      sql"""SELECT s.token, s.user_id, s.created_at, s.expires_at,
                   u.id, u.email, u.password_hash, u.display_name, u.role, u.active, u.created_at
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.token = $token AND s.expires_at > ${Util.nowMillis} AND u.active = TRUE"""
        .query[(String, Long, Long, Long, Long, String, String, String, String, Boolean, Long)]
        .option
    }.map { row =>
      row.map { case (tok, sUid, sCreated, sExpires, uid, email, hash, name, role, active, uCreated) =>
        (User(uid, email, hash, name, role, active, uCreated), Session(tok, sUid, sCreated, sExpires))
      }
    }

  def delete(token: String): IO[Int] =
    tx(sql"DELETE FROM sessions WHERE token = $token".update.run)

  def deleteExpired(): IO[Int] =
    tx(sql"DELETE FROM sessions WHERE expires_at <= ${Util.nowMillis}".update.run)

  def deleteAllForUser(userId: Long): IO[Int] =
    tx(sql"DELETE FROM sessions WHERE user_id = $userId".update.run)
}
