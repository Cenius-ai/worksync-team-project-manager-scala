package pm.repo

import cats.effect.IO
import doobie.implicits._
import doobie.util.transactor.Transactor
import pm.domain.CommentRow

final class CommentRepository(xa: Transactor[IO]) {
  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  def add(taskId: Long, authorId: Long, body: String): IO[Long] =
    tx(sql"""INSERT INTO comments (task_id, author_id, body, created_at)
             VALUES ($taskId, $authorId, $body, ${pm.Util.nowMillis})"""
      .update.withUniqueGeneratedKeys[Long]("id"))

  def listForTask(taskId: Long): IO[List[CommentRow]] =
    tx {
      sql"""SELECT c.id, c.task_id, c.author_id, c.body, c.created_at, u.display_name
            FROM comments c JOIN users u ON u.id = c.author_id
            WHERE c.task_id = $taskId
            ORDER BY c.created_at ASC""".query[(pm.domain.Comment, String)].to[List]
    }.map(_.map { case (c, name) => CommentRow(c, name) })

  def countForTask(taskId: Long): IO[Long] =
    tx(sql"SELECT COUNT(*) FROM comments WHERE task_id = $taskId".query[Long].unique)
}
