package pm.http

import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import pm.Env
import pm.Util
import pm.domain.{CommentRow, Project, Task, TaskListRow, Team, TimeRow}
import pm.views.{Layout, Pages, TaskForms, Ui}

/** Global task search/filter, task CRUD, comments and time tracking. */
object TaskRoutes {
  private def postCsrfOk(ctx: Web.Ctx, params: Map[String, String], header: Option[String]): Boolean =
    Web.csrfOk(params, header, ctx.csrf)

  private def csrfHeader(req: Request[IO]): Option[String] =
    req.headers.get(org.typelevel.ci.CIString("X-CSRF-Token")).map(_.head.value)

  private def validTitle(t: String): Option[String] =
    if (t.trim.isEmpty) Some("Task title is required.")
    else if (t.trim.length > 200) Some("Title must be 200 characters or fewer.")
    else None

  private val todayStr: String = java.time.LocalDate.now().toString

  /* ------------------------- search page ------------------------- */

  private def searchView(
      ctx: Web.Ctx,
      q: String,
      fStatus: Option[String],
      fPriority: Option[String],
      fProject: Option[Long],
      fTeam: Option[String],
      fAssignee: Option[String],
      allRows: List[TaskListRow],
      visible: List[TaskListRow]
  ): String = {
    val flash = ctx.flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")
    val empty =
      if (allRows.isEmpty)
        """<div class="surface mb-12">""" +
          Ui.emptyState(
            "No tasks yet",
            "Create a task from any project to start tracking work here.",
            Some("""<a class="btn btn-primary" href="/tasks/new">New task</a>""")
          ) +
          "</div>"
      else if (visible.isEmpty)
        """<div class="surface mb-12">""" +
          Ui.emptyState(
            "No tasks match your search",
            "Try a different keyword, reset the filters, or create a task.",
            Some("""<a class="btn btn-outline" href="/tasks">Reset filters</a>""")
          ) +
          "</div>"
      else ""

    val trs = visible.map { r =>
      val due = r.task.dueDate.map(Util.humanDate).getOrElse("—")
      val late = r.task.dueDate.exists(d => d < todayStr && r.task.status != Task.StatusDone)
      val dueHtml = if (late) s"""<span class="due-late">▲ $due</span>""" else s"""<span>$due</span>"""
      val assignee = r.assigneeName.getOrElse("Unassigned")
      s"""<tr>
           <td><a class="linkish" href="/tasks/${r.task.id}">${Ui.esc(r.task.title)}</a>
               <div class="sub">${Ui.esc(r.teamName)} · ${Ui.esc(r.projectName)}</div></td>
           <td>${Ui.statusPill(r.task.status)}</td>
           <td>${Ui.priorityPill(r.task.priority)}</td>
           <td><span class="avatar-row">${Ui.avatarSm(assignee, Some(assignee))} ${Ui.esc(assignee)}</span></td>
           <td class="nowrap">$dueHtml</td>
           <td class="td-right"><a class="btn btn-sm btn-outline" href="/tasks/${r.task.id}">Open →</a></td>
         </tr>"""
    }.mkString("\n")

    val table =
      if (visible.nonEmpty)
        s"""<div class="tablewrap"><div class="table-scroll">
             <table class="grid">
               <thead><tr><th>Task</th><th>Status</th><th>Priority</th><th>Assignee</th><th>Due</th><th></th></tr></thead>
               <tbody>$trs</tbody>
             </table>
           </div></div>"""
      else ""

    def sel(name: String, cur: Option[String], empty: String, opts: List[(String, String)]): String =
      s"""<label class="kicker" for="$name">$empty</label>
          <select class="input" id="$name" name="$name" style="width:auto" aria-label="$empty">
            <option value="">$empty</option>
            ${opts.map { case (v, l) =>
              val selected = if (cur.contains(v)) " selected" else ""
              s"""<option value="${Ui.esc(v)}"$selected>${Ui.esc(l)}</option>"""
            }.mkString("\n")}
          </select>"""

    val teamOptions = allRows.map(r => r.teamName -> r.teamName).distinct.sortBy(_._1.toLowerCase)
    val projectOptions = allRows.map(r => (r.projectId.toString, r.projectName)).distinct.sortBy(_._2.toLowerCase)
    val assigneeOptions =
      allRows.flatMap(r => r.task.assigneeId.map(id => (id.toString, r.assigneeName.getOrElse("Unassigned")))).distinct.sortBy(_._2.toLowerCase)
    val hasFilter = q.nonEmpty || fStatus.nonEmpty || fPriority.nonEmpty || fProject.nonEmpty || fTeam.nonEmpty || fAssignee.nonEmpty

    val filterBar =
      s"""<div class="filtermode">
           <span class="fm-label">Tasks · ${visible.size} of ${allRows.size}</span>
           <form class="filter-form" method="get" action="/tasks" role="search">
             <input class="input" type="search" name="q" value="${Ui.esc(q)}" placeholder="Search title or description…" aria-label="Search tasks" style="width:230px">
             ${sel("status", fStatus, "Any status", TaskForms.statusOptions)}
             ${sel("priority", fPriority, "Any priority", TaskForms.priorityOptions)}
             ${sel("team", fTeam, "All teams", teamOptions)}
             ${sel("project", fProject.map(_.toString), "All projects", projectOptions)}
             ${sel("assignee", fAssignee, "Anyone", assigneeOptions)}
             <button class="btn btn-sm btn-secondary" type="submit">Apply</button>
             ${if (hasFilter) """<a class="btn btn-sm btn-ghost-danger" href="/tasks">Reset</a>""" else ""}
           </form>
         </div>"""

    val header =
      s"""<div class="pagehead">
           <div>
             <div class="crumbs"><a href="/app">Dashboard</a><span class="breadcrumb-sep">/</span>Tasks</div>
             <h1>All tasks</h1>
             <p class="sub">${if (q.trim.nonEmpty) s"""Results for “${Ui.esc(q.trim)}”""" else "Everything across the projects you can access"}</p>
           </div>
           <div class="pagehead-actions"><a class="btn btn-primary" href="/tasks/new">+ New task</a></div>
         </div>"""

    val body = flash + header + filterBar + empty + table
    Layout.shell(ctx, "tasks", "Tasks", body, q)
  }

  private def search(ctx: Web.Ctx, env: Env): IO[Response[IO]] = {
    val params = ctx.req.params
    val q = params.getOrElse("q", "")
    val fStatus = params.get("status").filter(Task.Statuses.contains)
    val fPriority = params.get("priority").filter(Task.Priorities.contains)
    val fProject = params.get("project").flatMap(_.toLongOption)
    val fTeam = params.get("team").map(_.trim).filter(_.nonEmpty)
    val fAssignee = params.get("assignee").map(_.trim).filter(_.nonEmpty)
    env.tasks.accessibleRows(ctx.user.id, ctx.user.isAdmin).flatMap { all =>
      val ql = Option(q).map(_.trim.toLowerCase).filter(_.nonEmpty)
      val visible = all.filter { r =>
        val qOk = ql.forall { query =>
          r.task.title.toLowerCase.contains(query) || r.task.description.toLowerCase.contains(query)
        }
        val sOk = fStatus.forall(_ == r.task.status)
        val pOk = fPriority.forall(_ == r.task.priority)
        val prOk = fProject.forall(_ == r.projectId)
        val tOk = fTeam.forall(_.equalsIgnoreCase(r.teamName))
        val aOk = fAssignee match {
          case Some("none") => r.assigneeName.isEmpty
          case Some(v)      => r.task.assigneeId.contains(v.toLongOption.getOrElse(-1L))
          case None         => true
        }
        qOk && sOk && pOk && prOk && tOk && aOk
      }
      ctx.html(searchView(ctx, q, fStatus, fPriority, fProject, fTeam, fAssignee, all, visible))
    }
  }

  /* ------------------------- standalone create page ------------------------- */

  private def usableProjects(env: Env, ctx: Web.Ctx): IO[List[(Long, String, Long, String)]] =
    for {
      teams <- env.teams.listRows(ctx.user.id, ctx.user.isAdmin)
      activeTeamIds = teams.filterNot(_.team.archived).map(_.team.id).toSet
      projects <- env.projects.listRows(ctx.user.id, ctx.user.isAdmin)
    } yield projects
      .filter(p => p.project.status == Project.StatusActive && activeTeamIds.contains(p.project.teamId))
      .map(p => (p.project.id, p.project.name, p.project.teamId, p.teamName))

  private def newTaskView(
      ctx: Web.Ctx,
      projects: List[(Long, String)],
      assignees: List[(Long, String)],
      errors: Map[String, String],
      values: Map[String, String]
  ): String = {
    val flash = ctx.flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")
    val body =
      s"""<div class="pagehead">
           <div>
             <div class="crumbs"><a href="/app">Dashboard</a><span class="breadcrumb-sep">/</span><a href="/tasks">Tasks</a><span class="breadcrumb-sep">/</span>New task</div>
             <h1>New task</h1>
             <p class="sub">Assign work across any project you can access</p>
           </div>
         </div>
         $flash""" +
        (if (projects.isEmpty)
           Ui.emptyState(
             "No active projects to add tasks to",
             "Create a project inside one of your teams first, then come back to add tasks.",
             Some("""<a class="btn btn-primary" href="/projects">Go to projects</a>""")
           )
         else
           s"""<div class="surface sec">
                <div class="sec-head"><h2>Create a task</h2></div>
                <div class="sec-body">
                  ${TaskForms.createTaskForm(ctx.csrf, "/tasks/create", projects, None, assignees, errors, values, "Create task")}
                </div>
              </div>""")
    Layout.shell(ctx, "tasks", "New task", body)
  }

  private def newTaskPage(ctx: Web.Ctx, env: Env): IO[Response[IO]] =
    for {
      usable <- usableProjects(env, ctx)
      projectOpts = usable.map(u => u._1 -> s"${u._4} / ${u._2}")
      memberOpts <- assigneeOptionsFor(env, usable.map(_._3))
      page = newTaskView(ctx, projectOpts, memberOpts, Map.empty, Map.empty)
      resp <- ctx.html(page)
    } yield resp

  private def assigneeOptionsFor(env: Env, teamIds: List[Long]): IO[List[(Long, String)]] =
    teamIds.distinct.traverse(env.teams.members).map(_.flatten.map(m => m.userId -> m.displayName).distinct.sortBy(_._2.toLowerCase))

  /* ------------------------- create handler ------------------------- */

  private def createTask(ctx: Web.Ctx, env: Env, req: Request[IO]): IO[Response[IO]] =
    Web.formParams(req).flatMap { p =>
      val ok = postCsrfOk(ctx, p, csrfHeader(req))
      val projectId = p.get("projectId").flatMap(_.toLongOption)
      val title = p.getOrElse("title", "").trim
      val description = p.getOrElse("description", "")
      val status = p.getOrElse("status", Task.StatusTodo)
      val priority = p.getOrElse("priority", Task.PriorityMedium)
      val assigneeId = p.get("assigneeId").flatMap(_.toLongOption)
      val dueDate = p.get("dueDate").map(_.trim).filter(_.nonEmpty)

      var errors = Map.empty[String, String]
      validTitle(title).foreach(e => errors += "title" -> e)
      if (description.length > 8000) errors += "description" -> "Description must be 8000 characters or fewer."
      if (projectId.isEmpty) errors += "projectId" -> "Choose a project for this task."
      if (!Task.Statuses.contains(status)) errors += "status" -> "Choose a valid status."
      if (!Task.Priorities.contains(priority)) errors += "priority" -> "Choose a valid priority."
      dueDate.foreach { d =>
        if (Util.parseDate(d).isEmpty) errors += "dueDate" -> "Enter a valid date (yyyy-mm-dd)."
      }
      if (!ok) return Pages.forbidden(ctx, "Your session expired — please try again.")

      val projectOpt: IO[Option[(Project, Team)]] = projectId match {
        case Some(pid) => env.projects.withTeam(pid)
        case None      => IO.pure(None)
      }

      projectOpt.flatMap {
        case None =>
          renderCreateErrors(ctx, env, errors + ("projectId" -> "Choose a project for this task."), p)
        case Some((proj, team)) =>
          val allowed = ctx.user.isAdmin || (proj.status == Project.StatusActive && !team.archived)
          if (!allowed)
            ctx.redirect(s"/projects/${proj.id}", Some("That project is archived — only active projects accept new tasks."))
          else
            env.teams.members(team.id).flatMap { members =>
              val ids = members.map(_.userId).toSet
              if (assigneeId.exists(a => !ids.contains(a)))
                renderCreateErrors(ctx, env, errors + ("assigneeId" -> "Choose an assignee from the project's team."), p)
              else if (errors.nonEmpty)
                renderCreateErrors(ctx, env, errors, p)
              else
                env.tasks
                  .create(proj.id, title, description, status, priority, assigneeId, ctx.user.id, dueDate)
                  .flatMap { tid =>
                    ctx.redirect(s"/tasks/$tid", Some("Task created."))
                  }
            }
      }
    }

  private def renderCreateErrors(ctx: Web.Ctx, env: Env, errors: Map[String, String], p: Map[String, String]): IO[Response[IO]] =
    for {
      usable <- usableProjects(env, ctx)
      projectOpts = usable.map(u => u._1 -> s"${u._4} / ${u._2}")
      memberOpts <- assigneeOptionsFor(env, usable.map(_._3))
      page = newTaskView(ctx, projectOpts, memberOpts, errors, p)
      resp <- ctx.html(page, Status.BadRequest)
    } yield resp

  /* ------------------------- task detail ------------------------- */

  private def statusStepper(ctx: Web.Ctx, t: Task): String = {
    val order = List(
      Task.StatusTodo -> "To-do",
      Task.StatusInProgress -> "In progress",
      Task.StatusDone -> "Done"
    )
    val btns = order.zipWithIndex
      .map { case ((status, label), idx) =>
        val active = if (t.status == status) " active" else ""
        val confirm = if (t.status == status) "" else s""" data-confirm="Move this task to ${label.toLowerCase}?"""
        val sep = if (idx < order.size - 1) """<span class="status-arrow" aria-hidden="true">→</span>""" else ""
        s"""<form method="post" action="/tasks/${t.id}/status" class="nowrap"$confirm>
             ${Ui.csrf(ctx.csrf)}
             <input type="hidden" name="status" value="$status">
             <button class="btn btn-sm btn-secondary stepper-btn$active" type="submit">$label</button>
           </form>$sep"""
      }
      .mkString("\n")
    s"""<div class="stepper">$btns</div>"""
  }

  private def commentFeed(comments: List[CommentRow], taskId: Long, ctx: Web.Ctx, error: Option[String]): String = {
    val list =
      if (comments.isEmpty)
        Ui.emptyState("No comments yet", "Start the discussion — ask a question or leave an update for the team.")
      else
        comments
          .map { c =>
            val when = Util.relTime(c.comment.createdAt)
            s"""<div class="comment">
                 ${Ui.avatar(c.authorName)}
                 <div class="comment-body">
                   <div class="comment-meta"><span class="who">${Ui.esc(c.authorName)}</span>
                     <span class="when" data-ts="${c.comment.createdAt}">$when</span></div>
                   <div class="comment-text">${Ui.esc(c.comment.body)}</div>
                 </div>
               </div>"""
          }
          .mkString("\n")
    val composer =
      s"""<form method="post" action="/tasks/$taskId/comments" novalidate>
           ${Ui.csrf(ctx.csrf)}
           ${Ui.textareaField("body", "Add a comment", "", error, rows = 3, maxlength = Some(2000), help = Some("Up to 2000 characters. Plain text only."))}
           ${Ui.submit("addComment", "Post comment", "btn-secondary", Some("Posting…"))}
         </form>"""
    s"""<div class="sec-head"><h2>Comments <span class="sub">(${comments.size})</span></h2></div>
        <div class="sec-body"><div>$list</div>$composer</div>"""
  }

  private def timePanel(
      ctx: Web.Ctx,
      taskId: Long,
      time: List[TimeRow],
      total: Int,
      fieldErrors: Map[String, String],
      values: Map[String, String]
  ): String = {
    val today = java.time.LocalDate.now().toString
    val rows =
      if (time.isEmpty)
        Ui.emptyState("No time logged yet", "Log the minutes you spend on this task and they roll up into the dashboard's 7-day total.")
      else
        time
          .map { tr =>
            val when = Util.relTime(tr.entry.createdAt)
            s"""<tr>
                 <td class="nowrap">${Ui.esc(Util.humanDate(tr.entry.entryDate))}</td>
                 <td><span class="avatar-row">${Ui.avatarSm(tr.userName, Some(tr.userName))} ${Ui.esc(tr.userName)}</span></td>
                 <td class="num">${tr.entry.minutes}m</td>
                 <td class="sub">${Ui.esc(tr.entry.note.getOrElse(""))}</td>
                 <td class="nowrap sub" data-ts="${tr.entry.createdAt}">$when</td>
               </tr>"""
          }
          .mkString("\n")
    val list =
      s"""<div class="tablewrap"><div class="table-scroll">
           <table class="grid">
             <thead><tr><th>Date</th><th>Who</th><th class="num">Time</th><th>Note</th><th>Logged</th></tr></thead>
             <tbody>$rows</tbody>
             <tfoot><tr><td colspan="2"><strong>Total</strong></td>
               <td class="num"><strong>${Ui.esc(Util.fmtMinutes(total))}</strong></td><td colspan="2"></td></tr></tfoot>
           </table>
         </div></div>"""
    val minutesValue = values.getOrElse("minutes", "")
    val noteValue = values.getOrElse("note", "")
    val dateValue = values.getOrElse("entryDate", today)
    val form =
      s"""<form method="post" action="/tasks/$taskId/time" novalidate class="mt-8">
           ${Ui.csrf(ctx.csrf)}
           <div class="two-col-form">
             ${Ui.numberField("minutes", "Minutes worked", Some(minutesValue), fieldErrors.get("minutes"), required = true, min = Some(1), max = Some(1440), help = Some("Whole minutes, 1–1440"))}
             ${Ui.dateField("entryDate", "Date", Some(dateValue), fieldErrors.get("entryDate"), required = true)}
           </div>
           ${Ui.textField("note", "Note", noteValue, fieldErrors.get("note"), maxlength = Some(500), placeholder = "What did you work on?")}
           ${Ui.submit("addTime", "Log time", "btn-secondary", Some("Logging…"))}
         </form>"""
    s"""<div class="sec-head"><h2>Time logged <span class="sub">(${Ui.esc(Util.fmtMinutes(total))} total)</span></h2></div>
        <div class="sec-body">$list$form</div>"""
  }

  private def detailView(
      ctx: Web.Ctx,
      row: TaskListRow,
      team: Team,
      assignees: List[(Long, String)],
      comments: List[CommentRow],
      time: List[TimeRow],
      totalMinutes: Int,
      editErrors: Map[String, String],
      editValues: Map[String, String],
      commentError: Option[String],
      timeErrors: Map[String, String],
      timeValues: Map[String, String]
  ): String = {
    val t = row.task
    val flash = ctx.flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")
    val late = t.dueDate.exists(d => d < todayStr && t.status != Task.StatusDone)
    val lateTag = if (late) """ <span class="due-late">overdue</span>""" else ""
    val dueCell = t.dueDate match {
      case Some(d) if late => s"""<span class="due-late">▲ ${Ui.esc(Util.humanDate(d))}</span>"""
      case Some(d)         => Ui.esc(Util.humanDate(d))
      case None            => "—"
    }
    val metaRows = List(
      "Status" -> (Ui.statusPill(t.status) + lateTag),
      "Priority" -> Ui.priorityPill(t.priority),
      "Assignee" -> row.assigneeName
        .map(n => s"""<span class="avatar-row">${Ui.avatarSm(n, Some(n))} ${Ui.esc(n)}</span>""")
        .getOrElse("Unassigned"),
      "Reporter" -> Ui.esc(row.reporterName),
      "Due" -> dueCell,
      "Created" -> Ui.esc(Util.humanDateTime(t.createdAt)),
      "Updated" -> Ui.esc(Util.relTime(t.updatedAt))
    )

    val header =
      s"""<div class="pagehead">
           <div>
             <div class="crumbs"><a href="/app">Dashboard</a><span class="breadcrumb-sep">/</span><a href="/tasks">Tasks</a><span class="breadcrumb-sep">/</span><a href="/projects/${t.projectId}">${Ui.esc(row.projectName)}</a><span class="breadcrumb-sep">/</span>#${t.id}</div>
             <h1>${Ui.esc(t.title)}</h1>
             <p class="sub">${Ui.esc(row.teamName)} · ${Ui.esc(row.projectName)}</p>
           </div>
           <div class="pagehead-actions">
             <a class="btn btn-outline" href="#edit-task">Edit</a>
             <form method="post" action="/tasks/${t.id}/delete" class="nowrap" data-confirm="Delete this task, its comments and its time entries? This cannot be undone.">
               ${Ui.csrf(ctx.csrf)}
               <button class="btn btn-danger" type="submit">Delete…</button>
             </form>
           </div>
         </div>"""

    val mainCol =
      s"""<div class="surface sec">
           <div class="sec-head"><h2>Move task</h2><span class="sub">status changes apply immediately</span></div>
           <div class="sec-body">${statusStepper(ctx, t)}</div>
         </div>
         <div class="surface sec">
           <div class="sec-head"><h2>Description</h2></div>
           <div class="sec-body">${if (t.description.trim.isEmpty) """<p class="muted">No description yet.</p>""" else s"""<div class="desc-block">${Ui.esc(t.description)}</div>"""}
           </div>
         </div>
         <div class="surface sec">${commentFeed(comments, t.id, ctx, commentError)}</div>"""

    val sideCol =
      s"""<div class="surface sec">
           <div class="sec-head"><h2>Details</h2></div>
           <div class="sec-body">${Ui.kv(metaRows)}</div>
         </div>
         <div class="surface sec">${timePanel(ctx, t.id, time, totalMinutes, timeErrors, timeValues)}</div>
         <div class="surface sec" id="edit-task">
           <div class="sec-head"><h2>Edit task</h2></div>
           <div class="sec-body">
             ${TaskForms.editTaskForm(ctx.csrf, t.id, assignees, editErrors, editValues)}
           </div>
         </div>"""

    val body = flash + header + s"""<div class="grid-2"><div>$mainCol</div><div>$sideCol</div></div>"""
    Layout.shell(ctx, "tasks", t.title, body)
  }

  /** Resolve task access: returns (row, team) or an error response. */
  private def resolveTask(ctx: Web.Ctx, env: Env, id: Long): IO[Either[Response[IO], (TaskListRow, Team)]] =
    env.tasks.rowById(id).flatMap {
      case None => Pages.notFound(ctx, "This task does not exist or was deleted.").map(Left(_))
      case Some(row) =>
        val accessOk: IO[Boolean] =
          if (ctx.user.isAdmin) IO.pure(true) else env.tasks.isAccessible(id, ctx.user.id)
        accessOk.flatMap {
          case false => Pages.forbidden(ctx, "You are not a member of this task's team.").map(Left(_))
          case true =>
            env.tasks.context(id).flatMap {
              case Some((_, team, _, _)) => IO.pure(Right((row, team)))
              case None                  => Pages.notFound(ctx, "This task's project no longer exists.").map(Left(_))
            }
        }
    }

  private final case class DetailState(
      editErrors: Map[String, String] = Map.empty,
      editValues: Map[String, String] = Map.empty,
      commentError: Option[String] = None,
      timeErrors: Map[String, String] = Map.empty,
      timeValues: Map[String, String] = Map.empty
  )

  private def renderDetail(ctx: Web.Ctx, env: Env, row: TaskListRow, state: DetailState): IO[Response[IO]] =
    env.tasks.context(row.task.id).flatMap {
      case None => Pages.notFound(ctx, "This task's project no longer exists.")
      case Some((_, team, _, _)) =>
        for {
          members <- env.teams.members(team.id)
          assignees = members.map(m => m.userId -> m.displayName)
          comments <- env.comments.listForTask(row.task.id)
          time <- env.time.listForTask(row.task.id)
          total <- env.time.sumForTask(row.task.id)
          values =
            if (state.editValues.nonEmpty) state.editValues
            else
              Map(
                "title" -> row.task.title,
                "description" -> row.task.description,
                "status" -> row.task.status,
                "priority" -> row.task.priority,
                "assigneeId" -> row.task.assigneeId.map(_.toString).getOrElse(""),
                "dueDate" -> row.task.dueDate.getOrElse("")
              )
          page = detailView(ctx, row, team, assignees, comments, time, total, state.editErrors, values, state.commentError, state.timeErrors, state.timeValues)
          resp <- ctx.html(page)
        } yield resp
    }

  private def detail(ctx: Web.Ctx, env: Env, id: Long, state: DetailState = DetailState()): IO[Response[IO]] =
    env.tasks.rowById(id).flatMap {
      case None => Pages.notFound(ctx, "This task does not exist or was deleted.")
      case Some(row) =>
        val accessOk: IO[Boolean] =
          if (ctx.user.isAdmin) IO.pure(true) else env.tasks.isAccessible(id, ctx.user.id)
        accessOk.flatMap {
          case false => Pages.forbidden(ctx, "You are not a member of this task's team.")
          case true  => renderDetail(ctx, env, row, state)
        }
    }

  /* ------------------------- task mutations ------------------------- */

  private def editTask(ctx: Web.Ctx, env: Env, req: Request[IO], id: Long): IO[Response[IO]] =
    resolveTask(ctx, env, id).flatMap {
      case Left(resp) => IO.pure(resp)
      case Right((row, team)) =>
        Web.formParams(req).flatMap { p =>
          val ok = postCsrfOk(ctx, p, csrfHeader(req))
          if (!ok) Pages.forbidden(ctx, "Your session expired — please try again.")
          else {
            val title = p.getOrElse("title", "").trim
            val description = p.getOrElse("description", "")
            val status = p.getOrElse("status", row.task.status)
            val priority = p.getOrElse("priority", row.task.priority)
            val assigneeId = p.get("assigneeId").flatMap(_.toLongOption)
            val dueDate = p.get("dueDate").map(_.trim).filter(_.nonEmpty)

            var errors = Map.empty[String, String]
            validTitle(title).foreach(e => errors += "title" -> e)
            if (description.length > 8000) errors += "description" -> "Description must be 8000 characters or fewer."
            if (!Task.Statuses.contains(status)) errors += "status" -> "Choose a valid status."
            if (!Task.Priorities.contains(priority)) errors += "priority" -> "Choose a valid priority."
            dueDate.foreach(d => if (Util.parseDate(d).isEmpty) errors += "dueDate" -> "Enter a valid date (yyyy-mm-dd).")

            env.teams.members(team.id).flatMap { members =>
              val ids = members.map(_.userId).toSet
              val invalidAssignee = assigneeId.exists(a => !ids.contains(a))
              if (invalidAssignee) errors += "assigneeId" -> "Choose an assignee from the project's team."
              if (errors.nonEmpty)
                detail(ctx, env, id, DetailState(editErrors = errors, editValues = p)).map(_.withStatus(Status.BadRequest))
              else
                env.tasks
                  .update(id, title, description, status, priority, assigneeId, dueDate)
                  .flatMap(_ => ctx.redirect(s"/tasks/$id", Some("Task updated.")))
            }
          }
        }
    }

  private def changeStatus(ctx: Web.Ctx, env: Env, req: Request[IO], id: Long): IO[Response[IO]] =
    resolveTask(ctx, env, id).flatMap {
      case Left(resp) => IO.pure(resp)
      case Right(_) =>
        Web.formParams(req).flatMap { p =>
          val ok = postCsrfOk(ctx, p, csrfHeader(req))
          val status = p.getOrElse("status", "")
          if (!ok) Pages.forbidden(ctx, "Your session expired — please try again.")
          else if (!Task.Statuses.contains(status)) ctx.redirect(s"/tasks/$id", Some("Invalid status."))
          else env.tasks.updateStatus(id, status).flatMap(_ => ctx.redirect(s"/tasks/$id", Some("Status updated.")))
        }
    }

  private def deleteTask(ctx: Web.Ctx, env: Env, req: Request[IO], id: Long): IO[Response[IO]] =
    resolveTask(ctx, env, id).flatMap {
      case Left(resp) => IO.pure(resp)
      case Right((row, _)) =>
        Web.formParams(req).flatMap { p =>
          val ok = postCsrfOk(ctx, p, csrfHeader(req))
          if (!ok) Pages.forbidden(ctx, "Your session expired — please try again.")
          else
            env.tasks.delete(id).flatMap { _ =>
              ctx.redirect(s"/projects/${row.projectId}", Some(s"Task “${row.task.title}” deleted."))
            }
        }
    }

  private def addComment(ctx: Web.Ctx, env: Env, req: Request[IO], id: Long): IO[Response[IO]] =
    resolveTask(ctx, env, id).flatMap {
      case Left(resp) => IO.pure(resp)
      case Right(_) =>
        Web.formParams(req).flatMap { p =>
          val ok = postCsrfOk(ctx, p, csrfHeader(req))
          val body = p.getOrElse("body", "").trim
          if (!ok) Pages.forbidden(ctx, "Your session expired — please try again.")
          else if (body.isEmpty)
            detail(ctx, env, id, DetailState(commentError = Some("Comment cannot be empty."))).map(_.withStatus(Status.BadRequest))
          else if (body.length > 2000)
            detail(ctx, env, id, DetailState(commentError = Some("Comment must be 2000 characters or fewer."))).map(_.withStatus(Status.BadRequest))
          else
            env.comments.add(id, ctx.user.id, body).flatMap { _ =>
              ctx.redirect(s"/tasks/$id", Some("Comment added."))
            }
        }
    }

  private def addTime(ctx: Web.Ctx, env: Env, req: Request[IO], id: Long): IO[Response[IO]] =
    resolveTask(ctx, env, id).flatMap {
      case Left(resp) => IO.pure(resp)
      case Right(_) =>
        Web.formParams(req).flatMap { p =>
          val ok = postCsrfOk(ctx, p, csrfHeader(req))
          val minutesStr = p.getOrElse("minutes", "")
          val minutes = minutesStr.toIntOption
          val note = p.getOrElse("note", "").trim
          val entryDate = p.getOrElse("entryDate", "").trim
          if (!ok) Pages.forbidden(ctx, "Your session expired — please try again.")
          else {
            var errors = Map.empty[String, String]
            if (minutes.isEmpty) errors += "minutes" -> "Enter how many minutes you worked (1–1440)."
            else if (minutes.exists(m => m < 1 || m > 1440)) errors += "minutes" -> "Minutes must be between 1 and 1440."
            if (note.length > 500) errors += "note" -> "Note must be 500 characters or fewer."
            if (Util.parseDate(entryDate).isEmpty) errors += "entryDate" -> "Choose a valid date for this time entry."
            if (errors.nonEmpty)
              detail(ctx, env, id, DetailState(timeErrors = errors, timeValues = Map("minutes" -> minutesStr, "note" -> note, "entryDate" -> entryDate)))
                .map(_.withStatus(Status.BadRequest))
            else
              env.time
                .add(id, ctx.user.id, minutes.get, Option(note).filter(_.nonEmpty), entryDate)
                .flatMap(_ => ctx.redirect(s"/tasks/$id", Some(s"${Util.fmtMinutes(minutes.get)} logged against this task.")))
          }
        }
    }

  def apply(env: Env): Web.Ctx => HttpRoutes[IO] = ctx =>
    HttpRoutes.of[IO] {
      case GET -> Root / "tasks" =>
        search(ctx, env)
      case GET -> Root / "tasks" / "new" =>
        newTaskPage(ctx, env)
      case req @ POST -> Root / "tasks" / "create" =>
        createTask(ctx, env, req)
      case GET -> Root / "tasks" / LongVar(id) =>
        detail(ctx, env, id)
      case req @ POST -> Root / "tasks" / LongVar(id) / "edit" =>
        editTask(ctx, env, req, id)
      case req @ POST -> Root / "tasks" / LongVar(id) / "status" =>
        changeStatus(ctx, env, req, id)
      case req @ POST -> Root / "tasks" / LongVar(id) / "delete" =>
        deleteTask(ctx, env, req, id)
      case req @ POST -> Root / "tasks" / LongVar(id) / "comments" =>
        addComment(ctx, env, req, id)
      case req @ POST -> Root / "tasks" / LongVar(id) / "time" =>
        addTime(ctx, env, req, id)
    }
}
