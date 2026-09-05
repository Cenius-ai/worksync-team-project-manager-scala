package pm.domain

import java.time.LocalDate

/** Plain domain records — one row per model. Timestamps are stored as BIGINT
  * epoch-millis; dates as ISO `yyyy-MM-dd` strings (validated in code).
  */

final case class User(
    id: Long,
    email: String,
    passwordHash: String,
    displayName: String,
    role: String,
    active: Boolean,
    createdAt: Long
) {
  def isAdmin: Boolean = role == "Admin"
}

object User {
  val RoleAdmin  = "Admin"
  val RoleMember = "Member"
  val Roles: List[String] = List(RoleAdmin, RoleMember)
}

final case class Session(token: String, userId: Long, createdAt: Long, expiresAt: Long)

final case class Team(
    id: Long,
    name: String,
    description: String,
    ownerId: Long,
    archived: Boolean,
    createdAt: Long
)

final case class TeamMember(id: Long, teamId: Long, userId: Long, joinedAt: Long)

final case class Project(
    id: Long,
    teamId: Long,
    name: String,
    description: String,
    ownerId: Long,
    status: String,
    createdAt: Long
)

object Project {
  val StatusActive   = "Active"
  val StatusArchived = "Archived"
}

final case class Task(
    id: Long,
    projectId: Long,
    title: String,
    description: String,
    status: String,
    priority: String,
    assigneeId: Option[Long],
    reporterId: Long,
    dueDate: Option[String],
    createdAt: Long,
    updatedAt: Long
)

object Task {
  val StatusTodo       = "Todo"
  val StatusInProgress = "InProgress"
  val StatusDone       = "Done"
  val Statuses: List[String] = List(StatusTodo, StatusInProgress, StatusDone)

  val PriorityLow    = "Low"
  val PriorityMedium = "Medium"
  val PriorityHigh   = "High"
  val Priorities: List[String] = List(PriorityLow, PriorityMedium, PriorityHigh)
}

final case class Comment(id: Long, taskId: Long, authorId: Long, body: String, createdAt: Long)

final case class TimeEntry(
    id: Long,
    taskId: Long,
    userId: Long,
    minutes: Int,
    note: Option[String],
    entryDate: String,
    createdAt: Long
)

/* ---------------- view-model rows (joined reads) ---------------- */

final case class TeamListRow(team: Team, memberCount: Long, projectCount: Long)

final case class MemberRow(userId: Long, email: String, displayName: String, role: String, active: Boolean, joinedAt: Long)

final case class ProjectListRow(project: Project, teamName: String, taskCount: Long, doneCount: Long)

final case class TaskListRow(
    task: Task,
    projectId: Long,
    projectName: String,
    teamName: String,
    assigneeName: Option[String],
    reporterName: String,
    commentCount: Long,
    timeMinutes: Long
)

final case class DashboardStats(
    activeProjects: Long,
    openTasks: Long,
    assignedToMe: Long,
    timeLast7d: Long
)

final case class CommentRow(comment: Comment, authorName: String)

final case class TimeRow(entry: TimeEntry, userName: String)

final case class TaskDetail(
    row: TaskListRow,
    description: String,
    assignee: Option[(Long, String)],
    reporter: (Long, String),
    comments: List[CommentRow],
    time: List[TimeRow],
    timeTotalMinutes: Int
)

object Views {
  implicit class LocalDateOps(val d: LocalDate) extends AnyVal {
    def iso: String = d.toString
  }
  implicit class StringDateOps(val s: String) extends AnyVal {
    def toDate: Option[LocalDate] = pm.Util.parseDate(s)
  }
}
