package pm.repo

import cats.effect.IO
import doobie.implicits._
import doobie.util.transactor.Transactor
import pm.db.Metadata.given
import pm.domain.{MemberRow, Team, TeamListRow}

final class TeamRepository(xa: Transactor[IO]) {
  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  private val TeamCols = "id, name, description, owner_id, archived, created_at"
  private val teamSelect: doobie.Fragment =
    fr" SELECT" ++ doobie.Fragment.const(TeamCols) ++ fr" FROM teams"

  def create(name: String, description: String, ownerId: Long): IO[Long] =
    tx {
      for {
        id <- sql"""INSERT INTO teams (name, description, owner_id, archived, created_at)
                    VALUES ($name, $description, $ownerId, FALSE, ${pm.Util.nowMillis})"""
          .update.withUniqueGeneratedKeys[Long]("id")
        _ <- sql"INSERT INTO team_members (team_id, user_id, joined_at) VALUES ($id, $ownerId, ${pm.Util.nowMillis})".update.run
      } yield id
    }

  def update(id: Long, name: String, description: String): IO[Int] =
    tx(sql"UPDATE teams SET name = $name, description = $description WHERE id = $id".update.run)

  def setArchived(id: Long, archived: Boolean): IO[Int] =
    tx(sql"UPDATE teams SET archived = $archived WHERE id = $id".update.run)

  def delete(id: Long): IO[Int] =
    tx(sql"DELETE FROM teams WHERE id = $id".update.run)

  def getById(id: Long): IO[Option[Team]] =
    tx((teamSelect ++ fr" WHERE id = $id").query[Team].option)

  def exists(id: Long): IO[Boolean] =
    tx(sql"SELECT COUNT(*) FROM teams WHERE id = $id".query[Long].unique).map(_ > 0)

  /** Is the (non-admin) user a member of this team? */
  def isMember(teamId: Long, userId: Long): IO[Boolean] =
    tx(sql"SELECT COUNT(*) FROM team_members WHERE team_id = $teamId AND user_id = $userId".query[Long].unique).map(_ > 0)

  /** Rows the user may see: all teams for admins, joined teams otherwise. */
  def listRows(userId: Long, isAdmin: Boolean): IO[List[TeamListRow]] = {
    val base = sql"""SELECT t.id, t.name, t.description, t.owner_id, t.archived, t.created_at,
                            (SELECT COUNT(*) FROM team_members m WHERE m.team_id = t.id),
                            (SELECT COUNT(*) FROM projects p WHERE p.team_id = t.id)
                     FROM teams t"""
    val q =
      if (isAdmin) base ++ fr" ORDER BY t.archived ASC, lower(t.name) ASC"
      else
        base ++
          fr""" WHERE EXISTS (SELECT 1 FROM team_members tm WHERE tm.team_id = t.id AND tm.user_id = $userId)
               ORDER BY t.archived ASC, lower(t.name) ASC"""
    tx(q.query[(pm.domain.Team, Long, Long)].to[List]).map(_.map { case (team, mc, pc) =>
      TeamListRow(team, mc, pc)
    })
  }

  def members(teamId: Long): IO[List[MemberRow]] =
    tx {
      sql"""SELECT u.id, u.email, u.display_name, u.role, u.active, tm.joined_at
            FROM team_members tm
            JOIN users u ON u.id = tm.user_id
            WHERE tm.team_id = $teamId
            ORDER BY tm.joined_at ASC""".query[(Long, String, String, String, Boolean, Long)].to[List]
    }.map(_.map { case (uid, email, name, role, active, joined) =>
      MemberRow(uid, email, name, role, active, joined)
    })

  def addMember(teamId: Long, userId: Long): IO[Int] =
    tx(sql"INSERT INTO team_members (team_id, user_id, joined_at) VALUES ($teamId, $userId, ${pm.Util.nowMillis})".update.run)

  def removeMember(teamId: Long, userId: Long): IO[Int] =
    tx(sql"DELETE FROM team_members WHERE team_id = $teamId AND user_id = $userId".update.run)
}
