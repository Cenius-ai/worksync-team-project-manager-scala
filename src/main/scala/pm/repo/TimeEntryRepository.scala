package pm.repo

import cats.effect.IO
import doobie.implicits._
import doobie.util.transactor.Transactor
import pm.domain.TimeRow

final class TimeEntryRepository(xa: Transactor[IO]) {
  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  def add(taskId: Long, userId: Long, minutes: Int, note: Option[String], entryDate: String): IO[Long] =
    tx {
      sql"""INSERT INTO time_entries (task_id, user_id, minutes, note, entry_date, created_at)
            VALUES ($taskId, $userId, $minutes, $note, $entryDate, ${pm.Util.nowMillis})"""
        .update.withUniqueGeneratedKeys[Long]("id")
    }

  def listForTask(taskId: Long): IO[List[TimeRow]] =
    tx {
      sql"""SELECT te.id, te.task_id, te.user_id, te.minutes, te.note, te.entry_date, te.created_at, u.display_name
            FROM time_entries te JOIN users u ON u.id = te.user_id
            WHERE te.task_id = $taskId
            ORDER BY te.entry_date DESC, te.created_at DESC""".query[(pm.domain.TimeEntry, String)].to[List]
    }.map(_.map { case (e, name) => TimeRow(e, name) })

  def sumForTask(taskId: Long): IO[Int] =
    tx(sql"SELECT COALESCE(SUM(minutes), 0) FROM time_entries WHERE task_id = $taskId".query[Int].unique)

  /** Minutes logged on accessible tasks over the trailing 7 calendar days
    * (including today). Admin sees the whole workspace; members only the
    * tasks in teams they belong to.
    */
  def sumLast7d(userId: Long, isAdmin: Boolean): IO[Long] = {
    val since = java.time.LocalDate.now().minusDays(6).toString
    val base =
      sql"""SELECT COALESCE(SUM(te.minutes), 0)
            FROM time_entries te
            JOIN tasks tk ON tk.id = te.task_id
            JOIN projects p ON p.id = tk.project_id
            JOIN teams t ON t.id = p.team_id
            WHERE te.entry_date >= $since"""
    val q =
      if (isAdmin) base.query[Long]
      else
        (base ++ fr""" AND EXISTS (SELECT 1 FROM team_members tm WHERE tm.team_id = t.id AND tm.user_id = $userId)""").query[Long]
    tx(q.unique)
  }
}
