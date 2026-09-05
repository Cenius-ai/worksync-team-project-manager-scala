package pm.repo

import cats.effect.IO
import doobie.implicits._
import doobie.util.transactor.Transactor
import pm.db.Metadata.given
import pm.domain.{Project, Task, TaskListRow, Team}

import java.time.LocalDate

final class TaskRepository(xa: Transactor[IO]) {
  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  def create(
      projectId: Long,
      title: String,
      description: String,
      status: String,
      priority: String,
      assigneeId: Option[Long],
      reporterId: Long,
      dueDate: Option[String]
  ): IO[Long] =
    tx {
      val nowMs = pm.Util.nowMillis
      sql"""INSERT INTO tasks (project_id, title, description, status, priority, assignee_id, reporter_id, due_date, created_at, updated_at)
            VALUES ($projectId, $title, $description, $status, $priority, $assigneeId, $reporterId, $dueDate, $nowMs, $nowMs)"""
        .update.withUniqueGeneratedKeys[Long]("id")
    }

  def update(
      id: Long,
      title: String,
      description: String,
      status: String,
      priority: String,
      assigneeId: Option[Long],
      dueDate: Option[String]
  ): IO[Int] =
    tx {
      sql"""UPDATE tasks SET title = $title, description = $description, status = $status,
            priority = $priority, assignee_id = $assigneeId, due_date = $dueDate, updated_at = ${pm.Util.nowMillis}
            WHERE id = $id""".update.run
    }

  def updateStatus(id: Long, status: String): IO[Int] =
    tx(sql"UPDATE tasks SET status = $status, updated_at = ${pm.Util.nowMillis} WHERE id = $id".update.run)

  def delete(id: Long): IO[Int] =
    tx(sql"DELETE FROM tasks WHERE id = $id".update.run)

  def getById(id: Long): IO[Option[Task]] =
    tx(sql"SELECT id, project_id, title, description, status, priority, assignee_id, reporter_id, due_date, created_at, updated_at FROM tasks WHERE id = $id".query[Task].option)

  /** Project + team for a task, used by access checks and breadcrumbs. */
  def context(taskId: Long): IO[Option[(Project, Team, String, String)]] =
    tx {
      sql"""SELECT p.id, p.team_id, p.name, p.description, p.owner_id, p.status, p.created_at,
                   t.id, t.name, t.description, t.owner_id, t.archived, t.created_at,
                   p.name, t.name
            FROM tasks tk
            JOIN projects p ON p.id = tk.project_id
            JOIN teams t ON t.id = p.team_id
            WHERE tk.id = $taskId""".query[(Project, Team, String, String)].option
    }

  def isAccessible(taskId: Long, userId: Long): IO[Boolean] =
    tx {
      sql"""SELECT COUNT(*) FROM tasks tk
            JOIN projects p ON p.id = tk.project_id
            JOIN teams t ON t.id = p.team_id
            JOIN team_members tm ON tm.team_id = t.id AND tm.user_id = $userId
            WHERE tk.id = $taskId""".query[Long].unique
    }.map(_ > 0)

  def exists(taskId: Long): IO[Boolean] =
    tx(sql"SELECT COUNT(*) FROM tasks WHERE id = $taskId".query[Long].unique).map(_ > 0)

  /** List of tasks for one project (assignee/reporter names included). */
  def listForProject(projectId: Long): IO[List[(Task, Option[String], String)]] =
    tx {
      sql"""SELECT tk.id, tk.project_id, tk.title, tk.description, tk.status, tk.priority,
                   tk.assignee_id, tk.reporter_id, tk.due_date, tk.created_at, tk.updated_at,
                   au.display_name, ru.display_name
            FROM tasks tk
            LEFT JOIN users au ON au.id = tk.assignee_id
            JOIN users ru ON ru.id = tk.reporter_id
            WHERE tk.project_id = $projectId
            ORDER BY tk.created_at DESC""".query[(Task, Option[String], String)].to[List]
    }

  /** Board/count query for a project: (status, count). */
  def statusCountsForProject(projectId: Long): IO[List[(String, Long)]] =
    tx(sql"SELECT status, COUNT(*) FROM tasks WHERE project_id = $projectId GROUP BY status".query[(String, Long)].to[List])

  /** Scoped task rows across the user's accessible projects. */
  private val RowSql =
    sql"""SELECT tk.id, tk.project_id, tk.title, tk.description, tk.status, tk.priority,
                 tk.assignee_id, tk.reporter_id, tk.due_date, tk.created_at, tk.updated_at,
                 p.name, t.name, au.display_name, ru.display_name
          FROM tasks tk
          JOIN projects p ON p.id = tk.project_id
          JOIN teams t ON t.id = p.team_id
          LEFT JOIN users au ON au.id = tk.assignee_id
          JOIN users ru ON ru.id = tk.reporter_id"""

  private def scoped(isAdmin: Boolean, userId: Long) =
    if (isAdmin) fr" "
    else fr"  WHERE EXISTS (SELECT 1 FROM team_members tm WHERE tm.team_id = t.id AND tm.user_id = $userId)"

  private def assemble(rows: List[(Task, String, String, Option[String], String)]): List[TaskListRow] =
    rows.map { case (tk, pName, tName, aName, rName) =>
      TaskListRow(tk, tk.projectId, pName, tName, aName, rName, 0L, 0L)
    }

  /** Global search source: every accessible task (filters applied in memory
    * at the service layer so access rules and display stay in one place).
    */
  def accessibleRows(userId: Long, isAdmin: Boolean): IO[List[TaskListRow]] = {
    val q = RowSql ++ scoped(isAdmin, userId) ++ fr" ORDER BY tk.updated_at DESC"
    tx(q.query[(Task, String, String, Option[String], String)].to[List]).map(assemble)
  }

  /** Recently updated accessible tasks for the dashboard. */
  def recentRows(userId: Long, isAdmin: Boolean, limit: Int): IO[List[TaskListRow]] = {
    val q = RowSql ++ scoped(isAdmin, userId) ++ fr" ORDER BY tk.updated_at DESC LIMIT $limit"
    tx(q.query[(Task, String, String, Option[String], String)].to[List]).map(assemble)
  }

  /** One task row by id, or None when missing. Access decided by caller via
    * `isAccessible` (admins skip that check).
    */
  def rowById(taskId: Long): IO[Option[TaskListRow]] =
    tx {
      (RowSql ++ fr" WHERE tk.id = $taskId").query[(Task, String, String, Option[String], String)].option
    }.map(_.map { case (tk, pName, tName, aName, rName) =>
      TaskListRow(tk, tk.projectId, pName, tName, aName, rName, 0L, 0L)
    })

  def openAssignedTo(userId: Long): IO[Long] =
    tx {
      sql"""SELECT COUNT(*) FROM tasks tk
            JOIN projects p ON p.id = tk.project_id
            JOIN teams t ON t.id = p.team_id
            JOIN team_members tm ON tm.team_id = t.id AND tm.user_id = $userId
            WHERE tk.assignee_id = $userId AND tk.status <> 'Done'""".query[Long].unique
    }

  def dueDateSoon(withinDays: Long): String = LocalDate.now().plusDays(withinDays).toString
}
