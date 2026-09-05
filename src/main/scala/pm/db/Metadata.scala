package pm.db

import doobie.util.Read
import pm.domain.{Comment, Project, Session, Task, Team, TeamMember, TimeEntry, User}

/** Explicit Read instances for the plain domain records (mapped from scalar
  * tuples by column order). Keeps every repository free of per-query tuple
  * plumbing while staying independent of doobie's derivation modules.
  */
object Metadata {
  given Read[User] = Read[(Long, String, String, String, String, Boolean, Long)].map {
    case (id, email, passwordHash, displayName, role, active, createdAt) =>
      User(id, email, passwordHash, displayName, role, active, createdAt)
  }

  given Read[Session] = Read[(String, Long, Long, Long)].map {
    case (token, userId, createdAt, expiresAt) => Session(token, userId, createdAt, expiresAt)
  }

  given Read[Team] = Read[(Long, String, String, Long, Boolean, Long)].map {
    case (id, name, description, ownerId, archived, createdAt) =>
      Team(id, name, description, ownerId, archived, createdAt)
  }

  given Read[TeamMember] = Read[(Long, Long, Long, Long)].map {
    case (id, teamId, userId, joinedAt) => TeamMember(id, teamId, userId, joinedAt)
  }

  given Read[Project] = Read[(Long, Long, String, String, Long, String, Long)].map {
    case (id, teamId, name, description, ownerId, status, createdAt) =>
      Project(id, teamId, name, description, ownerId, status, createdAt)
  }

  given Read[Task] = Read[(Long, Long, String, String, String, String, Option[Long], Long, Option[String], Long, Long)].map {
    case (id, projectId, title, description, status, priority, assigneeId, reporterId, dueDate, createdAt, updatedAt) =>
      Task(id, projectId, title, description, status, priority, assigneeId, reporterId, dueDate, createdAt, updatedAt)
  }

  given Read[Comment] = Read[(Long, Long, Long, String, Long)].map {
    case (id, taskId, authorId, body, createdAt) => Comment(id, taskId, authorId, body, createdAt)
  }

  given Read[TimeEntry] = Read[(Long, Long, Long, Int, Option[String], String, Long)].map {
    case (id, taskId, userId, minutes, note, entryDate, createdAt) =>
      TimeEntry(id, taskId, userId, minutes, note, entryDate, createdAt)
  }
}
