package pm.repo

import cats.effect.IO
import doobie.implicits._
import doobie.util.transactor.Transactor
import pm.db.Metadata.given
import pm.domain.{Project, ProjectListRow, Team}

final class ProjectRepository(xa: Transactor[IO]) {
  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  def create(teamId: Long, name: String, description: String, ownerId: Long): IO[Long] =
    tx(sql"""INSERT INTO projects (team_id, name, description, owner_id, status, created_at)
             VALUES ($teamId, $name, $description, $ownerId, 'Active', ${pm.Util.nowMillis})"""
      .update.withUniqueGeneratedKeys[Long]("id"))

  def update(id: Long, name: String, description: String): IO[Int] =
    tx(sql"UPDATE projects SET name = $name, description = $description WHERE id = $id".update.run)

  def setStatus(id: Long, status: String): IO[Int] =
    tx(sql"UPDATE projects SET status = $status WHERE id = $id".update.run)

  def delete(id: Long): IO[Int] =
    tx(sql"DELETE FROM projects WHERE id = $id".update.run)

  def getById(id: Long): IO[Option[Project]] =
    tx {
      sql"""SELECT id, team_id, name, description, owner_id, status, created_at
            FROM projects WHERE id = $id""".query[Project].option
    }

  /** Project together with its team (always both present). */
  def withTeam(id: Long): IO[Option[(Project, Team)]] =
    tx {
      sql"""SELECT p.id, p.team_id, p.name, p.description, p.owner_id, p.status, p.created_at,
                   t.id, t.name, t.description, t.owner_id, t.archived, t.created_at
            FROM projects p JOIN teams t ON t.id = p.team_id
            WHERE p.id = $id""".query[(Project, Team)].option
    }

  def taskCount(projectId: Long): IO[Long] =
    tx(sql"SELECT COUNT(*) FROM tasks WHERE project_id = $projectId".query[Long].unique)

  /** True when another project in the same team already uses this name. */
  def duplicateName(teamId: Long, name: String, excludeId: Option[Long]): IO[Boolean] = {
    val q = sql"SELECT COUNT(*) FROM projects WHERE team_id = $teamId AND lower(name) = lower($name)"
    val full = excludeId match {
      case Some(id) => (q ++ fr" AND id <> $id").query[Long]
      case None     => q.query[Long]
    }
    tx(full.unique).map(_ > 0)
  }

  /** Active teams the user may create projects in: admins see all active
    * teams, members only the active teams they belong to.
    */
  def creatableTeams(userId: Long, isAdmin: Boolean): IO[List[(Long, String)]] = {
    val base = sql"SELECT t.id, t.name FROM teams t WHERE t.archived = FALSE"
    val q =
      if (isAdmin) base ++ fr" ORDER BY lower(t.name) ASC"
      else
        base ++
          fr""" AND EXISTS (SELECT 1 FROM team_members tm WHERE tm.team_id = t.id AND tm.user_id = $userId)
               ORDER BY lower(t.name) ASC"""
    tx(q.query[(Long, String)].to[List])
  }

  /** Rows the user may see (all for admins; joined teams otherwise). */
  def listRows(userId: Long, isAdmin: Boolean): IO[List[ProjectListRow]] = {
    val base = sql"""SELECT p.id, p.team_id, p.name, p.description, p.owner_id, p.status, p.created_at,
                            t.name,
                            (SELECT COUNT(*) FROM tasks k WHERE k.project_id = p.id),
                            (SELECT COUNT(*) FROM tasks k WHERE k.project_id = p.id AND k.status = 'Done')
                     FROM projects p JOIN teams t ON t.id = p.team_id"""
    val q =
      if (isAdmin) base ++ fr" ORDER BY CASE WHEN p.status = 'Archived' THEN 1 ELSE 0 END, lower(p.name) ASC"
      else
        base ++
          fr""" WHERE EXISTS (SELECT 1 FROM team_members tm WHERE tm.team_id = t.id AND tm.user_id = $userId)
               ORDER BY CASE WHEN p.status = 'Archived' THEN 1 ELSE 0 END, lower(p.name) ASC"""
    tx(q.query[(Project, String, Long, Long)].to[List]).map(_.map {
      case (p, teamName, tc, dc) => ProjectListRow(p, teamName, tc, dc)
    })
  }
}
