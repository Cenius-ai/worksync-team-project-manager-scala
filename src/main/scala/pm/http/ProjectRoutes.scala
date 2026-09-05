package pm.http

import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import pm.Env
import pm.Util
import pm.domain.{Project, ProjectListRow, Task, Team}
import pm.views.{Layout, Pages, TaskForms, Ui}

/** Projects index/detail, lifecycle (archive/restore/delete) and board. */
object ProjectRoutes {

  private def postCsrfOk(ctx: Web.Ctx, params: Map[String, String], header: Option[String]): Boolean =
    Web.csrfOk(params, header, ctx.csrf)

  private def csrfHeader(req: Request[IO]): Option[String] =
    req.headers.get(org.typelevel.ci.CIString("X-CSRF-Token")).map(_.head.value)

  private def validName(name: String): Option[String] =
    if (name.trim.length < 2) Some("Project name must be at least 2 characters.")
    else if (name.trim.length > 160) Some("Project name must be 160 characters or fewer.")
    else None

  /* ------------------------- index ------------------------- */

  private def indexView(
      ctx: Web.Ctx,
      q: String,
      teamFilter: Option[Long],
      status: String,
      rows: List[ProjectListRow],
      creatableTeams: List[(Long, String)],
      errors: Map[String, String],
      values: Map[String, String]
  ): String = {
    val flash = ctx.flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")
    val ql = Option(q).map(_.trim.toLowerCase).filter(_.nonEmpty)
    val visible = rows.filter { r =>
      val teamOk = teamFilter.forall(_ == r.project.teamId)
      val statusOk = status match {
        case "Active"   => r.project.status == Project.StatusActive
        case "Archived" => r.project.status == Project.StatusArchived
        case _          => true
      }
      val qOk = ql.forall { query =>
        r.project.name.toLowerCase.contains(query) || r.project.description.toLowerCase.contains(query) || r.teamName.toLowerCase.contains(query)
      }
      teamOk && statusOk && qOk
    }

    val tabs = List("all" -> "All", "Active" -> "Active", "Archived" -> "Archived")
    val tabLinks = tabs.map { case (key, label) =>
      val activeCls = if (status == key || (status == "" && key == "all")) " active" else ""
      val params = List(Some("status" -> key), Option(q).filter(_.nonEmpty).map("q" -> _), teamFilter.map("team" -> _.toString)).flatten
      val qs = if (params.isEmpty) "" else "?" + params.map { case (k, v) => s"${Util.urlEncode(k)}=${Util.urlEncode(v)}" }.mkString("&")
      s"""<a class="tab$activeCls" href="/projects$qs">$label</a>"""
    }.mkString("")

    val statusesCount = rows.groupBy(_.project.status).view.mapValues(_.size).toMap
    val emptyNote =
      if (rows.isEmpty && creatableTeams.isEmpty)
        """<div class="surface mb-12">""" +
          Ui.emptyState(
            "Projects live inside teams",
            "Create a team workspace first — then projects, tasks and boards will follow.",
            Some("""<a class="btn btn-primary" href="/teams">Go to teams</a>""")
          ) +
          "</div>"
      else if (visible.isEmpty)
        """<div class="surface mb-12">""" +
          Ui.emptyState(
            "No projects match",
            "Try clearing the filters, or create a project for a team you belong to.",
            Some("""<a class="btn btn-outline" href="/projects">Reset filters</a>""")
          ) +
          "</div>"
      else ""

    val table =
      if (visible.nonEmpty) {
        val trs = visible.map { r =>
          val p = r.project
          val pct = if (r.taskCount == 0) 0 else math.round(r.doneCount.toDouble * 100 / r.taskCount).toInt
          val statusCell = if (p.status == Project.StatusArchived) Ui.archivedPill() else Ui.pill("Active", "bg-brand")
          s"""<tr>
               <td><a class="linkish" href="/projects/${p.id}">${Ui.esc(p.name)}</a>
                   <div class="sub">${Ui.esc(p.description)}</div></td>
               <td>${Ui.esc(r.teamName)}</td>
               <td>$statusCell</td>
               <td class="td-num">${r.doneCount}/${r.taskCount}</td>
               <td class="td-num">$pct%</td>
               <td class="nowrap sub">${Util.humanDate(java.time.LocalDate.ofEpochDay(p.createdAt / 86400000L).toString)}</td>
               <td class="td-right"><a class="btn btn-sm btn-outline" href="/projects/${p.id}">Open →</a></td>
             </tr>"""
        }.mkString("\n")
        s"""<div class="tablewrap"><div class="table-scroll">
             <table class="grid">
               <thead><tr><th>Project</th><th>Team</th><th>Status</th><th class="num">Done</th><th class="num">Progress</th><th>Created</th><th></th></tr></thead>
               <tbody>$trs</tbody>
             </table>
           </div></div>"""
      } else ""

    val teamOptions = creatableTeams ++ rows.map(r => r.project.teamId -> r.teamName).distinct
    val teamSelect =
      s"""<label class="kicker" for="team">Team</label>
          <select class="input" id="team" name="team" style="width:auto">
            <option value="">All teams</option>
            ${teamOptions.sortBy(_._2.toLowerCase).distinct.map { case (id, n) =>
              val sel = if (teamFilter.contains(id)) " selected" else ""
              s"""<option value="$id"$sel>${Ui.esc(n)}</option>"""
            }.mkString("\n")}
          </select>"""

    val filterBar =
      s"""<div class="filtermode">
           <span class="fm-label">Projects</span>
           <div class="tabs">$tabLinks</div>
           <form class="filter-form" method="get" action="/projects" role="search">
             <input class="input" type="search" name="q" value="${Ui.esc(q)}" placeholder="Search projects…" aria-label="Search projects" style="width:220px">
             $teamSelect
             <input type="hidden" name="status" value="${Ui.esc(status)}">
             <button class="btn btn-sm btn-secondary" type="submit">Apply</button>
             ${if (q.nonEmpty || teamFilter.nonEmpty || status.nonEmpty) """<a class="btn btn-sm btn-ghost-danger" href="/projects">Reset</a>""" else ""}
           </form>
         </div>"""

    val createPanel =
      if (creatableTeams.isEmpty)
        """<div class="alert">You need to belong to an active team before you can create a project.</div>"""
      else
        s"""<div class="surface sec" id="create-project">
             <div class="sec-head"><h2>Create a project</h2></div>
             <div class="sec-body">
               <form method="post" action="/projects/create" novalidate>
                 ${Ui.csrf(ctx.csrf)}
                 <div class="two-col-form">
                   ${Ui.textField("name", "Project name", values.getOrElse("name", ""), errors.get("name"), required = true, placeholder = "e.g. Mobile app v2", maxlength = Some(160))}
                   ${Ui.selectField("teamId", "Team", createtableOptions(creatableTeams), values.get("teamId"), errors.get("teamId"), required = true, includeEmpty = Some("Choose a team…"))}
                 </div>
                 ${Ui.textareaField("description", "Description", values.getOrElse("description", ""), errors.get("description"), rows = 3, maxlength = Some(4000))}
                 ${Ui.submit("createProject", "Create project", "btn-brand", Some("Creating…"))}
               </form>
             </div>
           </div>"""

    val header =
      s"""<div class="pagehead">
           <div>
             <div class="crumbs"><a href="/app">Dashboard</a><span class="breadcrumb-sep">/</span>Projects</div>
             <h1>Projects</h1>
             <p class="sub">${rows.size} accessible · ${statusesCount.getOrElse("Active", 0)} active · ${statusesCount.getOrElse("Archived", 0)} archived</p>
           </div>
           <div class="pagehead-actions"><a class="btn btn-primary" href="#create-project">+ New project</a></div>
         </div>"""

    val body = flash + header + filterBar + emptyNote + table + createPanel
    Layout.shell(ctx, "projects", "Projects", body, q)
  }

  private def createtableOptions(teams: List[(Long, String)]): List[(String, String)] =
    teams.map { case (id, n) => id.toString -> n }

  private def index(ctx: Web.Ctx, env: Env): IO[Response[IO]] = {
    val params = ctx.req.params
    val q = params.getOrElse("q", "")
    val teamFilter = params.get("team").flatMap(_.toLongOption)
    val status = params.getOrElse("status", "all") match {
      case "Active" | "Archived" => params("status")
      case _                     => "all"
    }
    for {
      rows <- env.projects.listRows(ctx.user.id, ctx.user.isAdmin)
      creatable <- env.projects.creatableTeams(ctx.user.id, ctx.user.isAdmin)
      page = indexView(ctx, q, teamFilter, status, rows, creatable, Map.empty, Map.empty)
      resp <- ctx.html(page)
    } yield resp
  }

  private def create(ctx: Web.Ctx, env: Env, req: Request[IO]): IO[Response[IO]] =
    Web.formParams(req).flatMap { p =>
      val ok = postCsrfOk(ctx, p, csrfHeader(req))
      val name = p.getOrElse("name", "").trim
      val desc = p.getOrElse("description", "").trim
      val teamId = p.get("teamId").flatMap(_.toLongOption)
      var errors = Map.empty[String, String]
      validName(name).foreach(e => errors += "name" -> e)
      if (teamId.isEmpty) errors += "teamId" -> "Choose a team for this project."
      if (desc.length > 4000) errors += "description" -> "Description must be 4000 characters or fewer."
      if (!ok) errors += "_form" -> "Your session expired — please try again."

      def renderErr(extra: Map[String, String] = Map.empty): IO[Response[IO]] = {
        val all = errors ++ extra
        val clean = all - "_form"
        for {
          rows <- env.projects.listRows(ctx.user.id, ctx.user.isAdmin)
          creatable <- env.projects.creatableTeams(ctx.user.id, ctx.user.isAdmin)
          page = indexView(ctx, "", None, "all", rows, creatable, clean, Map("name" -> name, "description" -> desc, "teamId" -> teamId.map(_.toString).getOrElse("")))
          resp <- ctx.html(page, if (ok) Status.BadRequest else Status.Forbidden)
        } yield resp
      }

      val dupCheck: IO[Unit] = teamId match {
        case Some(tid) =>
          env.projects.duplicateName(tid, name, None).flatMap { dup =>
            if (dup) IO { errors += "name" -> "A project with this name already exists in that team." }
            else IO.unit
          }
        case None => IO.unit
      }

      if (!ok) renderErr()
      else if (errors.nonEmpty) renderErr()
      else
        dupCheck.flatMap { _ =>
          if (errors.nonEmpty) renderErr()
          else
            teamId match {
              case Some(tid) =>
                env.teams.isMember(tid, ctx.user.id).flatMap { member =>
                  val admin = ctx.user.isAdmin
                  if (!admin && !member)
                    Pages.forbidden(ctx, "You can only create projects in teams you belong to.")
                  else
                    env.projects.create(tid, name, desc, ctx.user.id).flatMap { pid =>
                      ctx.redirect(s"/projects/$pid", Some(s"Project “$name” created — add your first task below."))
                    }
                }
              case None => renderErr()
            }
        }
    }

  /* ------------------------- detail ------------------------- */

  private def board(ctx: Web.Ctx, env: Env, project: Project, team: Team, canManage: Boolean): IO[String] = {
    val colDefs = List(
      Task.StatusTodo -> "To-do",
      Task.StatusInProgress -> "In progress",
      Task.StatusDone -> "Done"
    )
    val colColors = Map(Task.StatusTodo -> "#55789a", Task.StatusInProgress -> "#c07b1c", Task.StatusDone -> "#2f9d6d")
    for {
      rows <- env.tasks.listForProject(project.id)
      members <- env.teams.members(team.id)
    } yield {
      val today = java.time.LocalDate.now().toString
      val cols = colDefs.map { case (status, label) =>
        val items = rows.filter(_._1.status == status)
        val cards = items.map { case (t, assigneeName, _) =>
          val late = t.dueDate.exists(d => d < today && t.status != Task.StatusDone)
          val dueHtml = t.dueDate match {
            case Some(d) if late => s"""<span class="due-late">▲ ${Ui.esc(Util.humanDate(d))}</span>"""
            case Some(d)         => s"""<span>${Ui.esc(Util.humanDate(d))}</span>"""
            case None            => ""
          }
          val prio = Ui.priorityPill(t.priority)
          val assignee = assigneeName.map(n => Ui.avatarSm(n, Some(n))).getOrElse("""<span class="muted small">unassigned</span>""")
          s"""<a class="board-card" href="/tasks/${t.id}">
               <div class="t">${Ui.esc(t.title)}</div>
               <div class="meta">$prio$dueHtml</div>
               <div class="meta">$assignee</div>
             </a>"""
        }.mkString("\n")
        val empty = if (items.isEmpty) """<div class="board-empty">No tasks here yet</div>""" else ""
        val dot = colColors(status)
        s"""<div class="board-col">
             <div class="bc-head"><span><span class="badge-dot" style="background:$dot" aria-hidden="true"></span> ${Ui.esc(label)}</span>
               <span class="bc-count">${items.size}</span></div>
             $cards$empty
           </div>"""
      }.mkString("\n")
      cols
    }
  }

  private def detailView(
      ctx: Web.Ctx,
      project: Project,
      team: Team,
      teamMembers: List[(Long, String)],
      boardHtml: String,
      canManage: Boolean,
      errors: Map[String, String],
      values: Map[String, String],
      newTaskErrors: Map[String, String],
      newTaskValues: Map[String, String]
  ): String = {
    val flash = ctx.flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")
    val archivedBanner =
      if (project.status == Project.StatusArchived)
        """<div class="alert warn">This project is archived. Tasks are read-only for the team; restore it to keep working.</div>"""
      else ""

    val manageActions =
      if (canManage)
        s"""<div class="pagehead-actions">
             <a class="btn btn-primary" href="#new-task">+ New task</a>
             <a class="btn btn-outline" href="#edit-project">Edit</a>
             <form method="post" action="/projects/${project.id}/${if (project.status == Project.StatusArchived) "restore" else "archive"}" class="nowrap"
                   data-confirm="${if (project.status == Project.StatusArchived) "Restore this project?" else "Archive this project? Tasks are kept."}">
               ${Ui.csrf(ctx.csrf)}
               <button class="btn btn-secondary" type="submit">${if (project.status == Project.StatusArchived) "Restore" else "Archive"}</button>
             </form>
             <form method="post" action="/projects/${project.id}/delete" class="nowrap"
                   data-confirm="Delete this project permanently? Only empty projects can be deleted.">
               ${Ui.csrf(ctx.csrf)}
               <button class="btn btn-danger" type="submit">Delete…</button>
             </form>
           </div>"""
      else ""

    val statusCell = if (project.status == Project.StatusArchived) Ui.archivedPill() else Ui.pill("Active", "bg-brand")
    val ownerName = teamMembers.find(_._1 == project.ownerId).map(_._2).getOrElse("Unknown")
    val memberChips = teamMembers.map { case (_, name) => Ui.avatarSm(name, Some(name)) }.mkString(" ")

    val header =
      s"""<div class="pagehead">
           <div>
             <div class="crumbs"><a href="/app">Dashboard</a><span class="breadcrumb-sep">/</span><a href="/teams">Teams</a><span class="breadcrumb-sep">/</span><a href="/teams/${team.id}">${Ui.esc(team.name)}</a><span class="breadcrumb-sep">/</span>${Ui.esc(project.name)}</div>
             <h1>${Ui.esc(project.name)} $statusCell</h1>
             <p class="sub">${Ui.esc(project.description)}</p>
           </div>
           $manageActions
         </div>"""

    val meta =
      s"""<div class="surface sec">
           <div class="sec-head"><h2>About this project</h2></div>
           <div class="sec-body">
             ${Ui.kv(List(
               "Team" -> s"""<a href="/teams/${team.id}">${Ui.esc(team.name)}</a>""",
               "Owner" -> Ui.esc(ownerName),
               "Status" -> statusCell,
               "Created" -> Ui.esc(Util.humanDate(java.time.LocalDate.ofEpochDay(project.createdAt / 86400000L).toString)),
               "Team members" -> s"""<span class="avatar-row">$memberChips</span>"""
             ))}
             <div class="desc-block mt-8">${Ui.esc(project.description)}</div>
           </div>
         </div>"""

    val editPanel =
      if (canManage)
        s"""<div class="surface sec" id="edit-project">
             <div class="sec-head"><h2>Edit project</h2></div>
             <div class="sec-body">
               <form method="post" action="/projects/${project.id}/edit" novalidate>
                 ${Ui.csrf(ctx.csrf)}
                 ${Ui.textField("name", "Project name", values.getOrElse("name", project.name), errors.get("name"), required = true, maxlength = Some(160))}
                 ${Ui.textareaField("description", "Description", values.getOrElse("description", project.description), errors.get("description"), rows = 3, maxlength = Some(4000))}
                 ${Ui.submit("editProject", "Save changes", "btn-brand", Some("Saving…"))}
               </form>
             </div>
           </div>"""
      else ""

    val newTaskPanel =
      s"""<div class="surface sec" id="new-task">
           <div class="sec-head"><h2>New task</h2><span class="sub">for ${Ui.esc(project.name)}</span></div>
           <div class="sec-body">
             ${TaskForms.createTaskForm(ctx.csrf, "/tasks/create", Nil, Some(project.id), teamMembers, newTaskErrors, newTaskValues)}
           </div>
         </div>"""

    val boardPanel =
      s"""<div class="surface sec">
           <div class="sec-head"><h2>Task board</h2><a class="small" href="/tasks?project=${project.id}">Search view →</a></div>
           <div class="sec-body"><div class="board">$boardHtml</div></div>
         </div>"""

    val body = flash + archivedBanner + header + meta + boardPanel + newTaskPanel + editPanel
    Layout.shell(ctx, "projects", project.name, body)
  }


  private def detail(ctx: Web.Ctx, env: Env, id: Long, errors: Map[String, String] = Map.empty, values: Map[String, String] = Map.empty): IO[Response[IO]] =
    env.projects.withTeam(id).flatMap {
      case None => Pages.notFound(ctx, "This project does not exist or was deleted.")
      case Some((p, t)) =>
        val isAdmin = ctx.user.isAdmin
        if (isAdmin) renderDetail(ctx, env, p, t, canManage = true, errors, values)
        else
          env.teams.isMember(t.id, ctx.user.id).flatMap {
            case false => Pages.forbidden(ctx, "You are not a member of this project's team.")
            case true =>
              val manage = p.ownerId == ctx.user.id || t.ownerId == ctx.user.id
              renderDetail(ctx, env, p, t, manage, errors, values)
          }
    }

  private def renderDetail(
      ctx: Web.Ctx,
      env: Env,
      p: Project,
      t: Team,
      canManage: Boolean,
      errors: Map[String, String],
      values: Map[String, String]
  ): IO[Response[IO]] =
    for {
      members <- env.teams.members(t.id)
      teamMembers = members.map(m => m.userId -> m.displayName)
      boardHtml <- board(ctx, env, p, t, canManage)
      page = detailView(ctx, p, t, teamMembers, boardHtml, canManage, errors, values, Map.empty, Map.empty)
      resp <- ctx.html(page)
    } yield resp

  private def edit(ctx: Web.Ctx, env: Env, req: Request[IO], id: Long): IO[Response[IO]] =
    env.projects.withTeam(id).flatMap {
        case None => Pages.notFound(ctx, "This project does not exist.")
        case Some((p, t)) =>
          val canManage = ctx.user.isAdmin || p.ownerId == ctx.user.id || t.ownerId == ctx.user.id
          if (!canManage) Pages.forbidden(ctx, "Only the project owner, team owner or an admin can edit this project.")
          else
            Web.formParams(req).flatMap { params =>
              val ok = postCsrfOk(ctx, params, csrfHeader(req))
              val name = params.getOrElse("name", "").trim
              val desc = params.getOrElse("description", "").trim
              var errors = Map.empty[String, String]
              validName(name).foreach(e => errors += "name" -> e)
              if (desc.length > 4000) errors += "description" -> "Description must be 4000 characters or fewer."
              if (!ok) errors += "_form" -> "Your session expired — please try again."
              val duplicate = env.projects.duplicateName(t.id, name, Some(p.id))
              val finish: IO[Response[IO]] =
                if (errors.nonEmpty || !ok) renderDetail(ctx, env, p, t, canManage, errors - "_form", Map("name" -> name, "description" -> desc))
                else
                  duplicate.flatMap { dup =>
                    if (dup) renderDetail(ctx, env, p, t, canManage, Map("name" -> "A project with this name already exists in that team."), Map("name" -> name, "description" -> desc))
                    else env.projects.update(id, name, desc).flatMap(_ => ctx.redirect(s"/projects/$id", Some("Project details saved.")))
                  }
              finish
            }
    }

  private def setStatus(ctx: Web.Ctx, env: Env, id: Long, archived: Boolean): IO[Response[IO]] =
    env.projects.withTeam(id).flatMap {
      case None => Pages.notFound(ctx, "This project does not exist.")
      case Some((p, t)) =>
        val canManage = ctx.user.isAdmin || p.ownerId == ctx.user.id || t.ownerId == ctx.user.id
        if (!canManage) Pages.forbidden(ctx, "Only the project owner, team owner or an admin can change this project.")
        else
          env.projects.setStatus(id, if (archived) Project.StatusArchived else Project.StatusActive).flatMap { _ =>
            ctx.redirect(s"/projects/$id", Some(if (archived) "Project archived." else "Project restored to active."))
          }
    }

  private def deleteProject(ctx: Web.Ctx, env: Env, id: Long): IO[Response[IO]] =
    env.projects.withTeam(id).flatMap {
      case None => Pages.notFound(ctx, "This project does not exist.")
      case Some((p, t)) =>
        val canManage = ctx.user.isAdmin || p.ownerId == ctx.user.id || t.ownerId == ctx.user.id
        if (!canManage) Pages.forbidden(ctx, "Only the project owner, team owner or an admin can delete this project.")
        else
          env.projects.taskCount(id).flatMap { count =>
            if (count > 0)
              ctx.redirect(s"/projects/$id", Some("Cannot delete: the project still has tasks. Archive it instead, or delete its tasks first."))
            else
              env.projects.delete(id).flatMap { _ =>
                ctx.redirect(s"/teams/${t.id}", Some(s"Project “${p.name}” deleted."))
              }
          }
    }

  def apply(env: Env): Web.Ctx => HttpRoutes[IO] = ctx =>
    HttpRoutes.of[IO] {
      case GET -> Root / "projects" =>
        index(ctx, env)
      case req @ POST -> Root / "projects" / "create" =>
        create(ctx, env, req)
      case GET -> Root / "projects" / LongVar(id) =>
        detail(ctx, env, id)
      case req @ POST -> Root / "projects" / LongVar(id) / "edit" =>
        edit(ctx, env, req, id)
      case POST -> Root / "projects" / LongVar(id) / "archive" =>
        setStatus(ctx, env, id, archived = true)
      case POST -> Root / "projects" / LongVar(id) / "restore" =>
        setStatus(ctx, env, id, archived = false)
      case POST -> Root / "projects" / LongVar(id) / "delete" =>
        deleteProject(ctx, env, id)
    }
}
