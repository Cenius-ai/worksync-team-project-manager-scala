package pm.repo

import cats.effect.IO
import doobie.implicits._
import doobie.util.transactor.Transactor

/** SQL aggregates powering the dashboard — scoped to what the current user
  * can access (all rows for admins; rows in joined teams for members). Every
  * value is bound as a parameter; no user text reaches the SQL text.
  */
final class DashboardRepository(xa: Transactor[IO]) {
  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  /** Team-level membership predicate used by every scoped query. */
  private def scope(userId: Long, isAdmin: Boolean): doobie.Fragment =
    if (isAdmin) fr" TRUE"
    else fr" EXISTS (SELECT 1 FROM team_members tm WHERE tm.team_id = t.id AND tm.user_id = $userId)"

  /** (activeProjects, openTasks, assignedToMe, timeLast7d) */
  def stats(userId: Long, isAdmin: Boolean): IO[(Long, Long, Long, Long)] = {
    val sc = scope(userId, isAdmin)
    val since = java.time.LocalDate.now().minusDays(6).toString
    val activeProjects =
      (fr" SELECT COUNT(*) FROM projects p JOIN teams t ON t.id = p.team_id" ++
        fr" WHERE p.status = 'Active' AND t.archived = FALSE AND" ++ sc).query[Long].unique
    val openTasks =
      (fr" SELECT COUNT(*) FROM tasks tk JOIN projects p ON p.id = tk.project_id JOIN teams t ON t.id = p.team_id" ++
        fr" WHERE tk.status <> 'Done' AND" ++ sc).query[Long].unique
    val assignedToMe =
      (fr" SELECT COUNT(*) FROM tasks tk JOIN projects p ON p.id = tk.project_id JOIN teams t ON t.id = p.team_id" ++
        fr" WHERE tk.assignee_id = $userId AND tk.status <> 'Done' AND" ++ sc).query[Long].unique
    val timeLast7d =
      (fr""" SELECT COALESCE(SUM(te.minutes), 0) FROM time_entries te
            JOIN tasks tk ON tk.id = te.task_id
            JOIN projects p ON p.id = tk.project_id JOIN teams t ON t.id = p.team_id""" ++
        fr" WHERE te.entry_date >= $since AND" ++ sc).query[Long].unique
    for {
      ap <- tx(activeProjects)
      ot <- tx(openTasks)
      am <- tx(assignedToMe)
      tl <- tx(timeLast7d)
    } yield (ap, ot, am, tl)
  }

  /** Task status distribution across accessible tasks. */
  def statusDistribution(userId: Long, isAdmin: Boolean): IO[List[(String, Long)]] = {
    val sc = scope(userId, isAdmin)
    val q =
      (fr""" SELECT tk.status, COUNT(*) FROM tasks tk
            JOIN projects p ON p.id = tk.project_id JOIN teams t ON t.id = p.team_id""" ++
        fr" WHERE" ++ sc ++ fr" GROUP BY tk.status").query[(String, Long)].to[List]
    tx(q)
  }
}
