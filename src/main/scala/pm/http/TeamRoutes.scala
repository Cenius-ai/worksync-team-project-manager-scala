package pm.http

import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import pm.Env
import pm.Util
import pm.domain.{MemberRow, Team, TeamListRow}
import pm.views.{Layout, Pages, Ui}

/** Teams index + detail, CRUD, archive/restore, membership management. */
object TeamRoutes {
  private def validName(name: String): Option[String] =
    if (name.trim.length < 2) Some("Team name must be at least 2 characters.")
    else if (name.trim.length > 160) Some("Team name must be 160 characters or fewer.")
    else None

  private def validDesc(desc: String): Option[String] =
    if (desc.length > 2000) Some("Description must be 2000 characters or fewer.")
    else None

  private def postCsrfOk(ctx: Web.Ctx, params: Map[String, String], header: Option[String]): Boolean =
    Web.csrfOk(params, header, ctx.csrf)

  private def csrfHeader(req: Request[IO]): Option[String] =
    req.headers.get(org.typelevel.ci.CIString("X-CSRF-Token")).map(_.head.value)

  /* ------------------------- index ------------------------- */

  private def indexView(ctx: Web.Ctx, q: String, teams: List[TeamListRow], errors: Map[String, String], values: Map[String, String]): String = {
    val flash = ctx.flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")
    val qFiltered = Option(q).map(_.trim.toLowerCase).filter(_.nonEmpty)
    val visible = qFiltered match {
      case Some(query) => teams.filter(t => t.team.name.toLowerCase.contains(query) || t.team.description.toLowerCase.contains(query))
      case None        => teams
    }

    val emptyNote =
      if (teams.isEmpty)
        """<div class="surface mb-12">""" +
          Ui.emptyState(
            "No teams yet",
            "Projects live inside team workspaces. Create your first team — you'll become its owner and first member.",
            Some("""<a class="btn btn-primary" href="#create-team">Create a team</a>""")
          ) +
          "</div>"
      else if (visible.isEmpty)
        """<div class="surface mb-12">""" +
          Ui.emptyState(
            s"No teams match “${q.trim}”",
            "Try a different name or clear the search.",
            Some("""<a class="btn btn-outline" href="/teams">Clear search</a>""")
          ) +
          "</div>"
      else ""

    val rows = visible.map { r =>
      val t = r.team
      val nameTd = s"""<td><a class="linkish" href="/teams/${t.id}">${Ui.esc(t.name)}</a>
                        <div class="sub">${Ui.esc(t.description)}</div></td>"""
      val statusTd = if (t.archived) s"<td>${Ui.archivedPill()}</td>" else s"""<td>${Ui.pill("Active", "bg-brand")}</td>"""
      s"""<tr>
            $nameTd
            <td class="td-num">${r.memberCount}</td>
            <td class="td-num">${r.projectCount}</td>
            $statusTd
            <td class="td-right row-actions">
              <a class="btn btn-sm btn-outline" href="/teams/${t.id}">Open →</a>
            </td>
          </tr>"""
    }.mkString("\n")

    val table =
      if (visible.nonEmpty)
        s"""<div class="tablewrap"><div class="table-scroll">
             <table class="grid">
               <thead><tr><th>Team</th><th class="num">Members</th><th class="num">Projects</th><th>Status</th><th></th></tr></thead>
               <tbody>$rows</tbody>
             </table>
           </div></div>"""
      else ""

    val createForm =
      s"""<div class="surface sec" id="create-team">
           <div class="sec-head"><h2>Create a team</h2></div>
           <div class="sec-body">
             <form method="post" action="/teams/create" novalidate>
               ${Ui.csrf(ctx.csrf)}
               <div class="two-col-form">
                 ${Ui.textField("name", "Team name", values.getOrElse("name", ""), errors.get("name"), required = true, placeholder = "e.g. Platform Engineering", maxlength = Some(160))}
                 ${Ui.textareaField("description", "Description", values.getOrElse("description", ""), errors.get("description"), rows = 3, maxlength = Some(2000), help = Some("What does this workspace do?"))}
               </div>
               ${Ui.submit("createTeam", "Create team", "btn-brand", Some("Creating…"))}
             </form>
           </div>
         </div>"""

    val searchForm =
      s"""<form class="filter-form" method="get" action="/teams" role="search">
           <label class="kicker" for="q">Filter</label>
           <input class="input"  type="search" id="q" name="q" value="${Ui.esc(q)}" placeholder="Filter by name…" aria-label="Filter teams by name">
           <button class="btn btn-sm btn-secondary" type="submit">Apply</button>
           ${if (q.nonEmpty) s"""<a class="btn btn-sm btn-ghost-danger" href="/teams">Clear</a>""" else ""}
         </form>"""

    val header =
      s"""<div class="pagehead">
           <div>
             <div class="crumbs"><a href="/app">Dashboard</a><span class="breadcrumb-sep">/</span>Teams</div>
             <h1>Teams</h1>
             <p class="sub">${teams.size} workspace${if (teams.size == 1) "" else "s"} you can access</p>
           </div>
           <div class="pagehead-actions"><a class="btn btn-primary" href="#create-team">+ New team</a></div>
         </div>"""

    val body = flash + header + searchForm + emptyNote + table + createForm
    Layout.shell(ctx, "teams", "Teams", body)
  }

  private def index(ctx: Web.Ctx, env: Env): IO[Response[IO]] = {
    val q = ctx.req.params.getOrElse("q", "")
    env.teams.listRows(ctx.user.id, ctx.user.isAdmin).flatMap { rows =>
      ctx.html(indexView(ctx, q, rows, Map.empty, Map.empty))
    }
  }

  private def create(ctx: Web.Ctx, env: Env, req: Request[IO]): IO[Response[IO]] =
    Web.formParams(req).flatMap { p =>
      val ok = postCsrfOk(ctx, p, csrfHeader(req))
      val name = p.getOrElse("name", "").trim
      val desc = p.getOrElse("description", "").trim
      var errors = Map.empty[String, String]
      validName(name).foreach(e => errors += "name" -> e)
      validDesc(desc).foreach(e => errors += "description" -> e)
      if (!ok) errors += "_form" -> "Your session expired — please try again."
      if (errors.nonEmpty) {
        val flash = errors.get("_form").map(m => m) // surfaced inline on re-render
        val errs = if (flash.nonEmpty) errors - "_form" else errors
        val q = ""
        env.teams.listRows(ctx.user.id, ctx.user.isAdmin).flatMap { rows =>
          ctx.html(indexView(ctx, q, rows, errs, Map("name" -> name, "description" -> desc)),
            if (ok) Status.BadRequest else Status.Forbidden)
        }
      } else
        env.teams.create(name, desc, ctx.user.id).flatMap { id =>
          ctx.redirect(s"/teams/$id", Some(s"Team “$name” created. You are its owner and first member."))
        }
    }

  /* ------------------------- detail ------------------------- */

  private def memberTable(csrf: String, team: Team, members: List[MemberRow], manage: Boolean): String = {
    if (members.isEmpty)
      Ui.emptyState("No members yet", "Add teammates by email so they can collaborate on this workspace's projects.")
    else {
      val joinedDate = (m: MemberRow) =>
        Util.humanDate(
          java.time.LocalDate
            .ofInstant(java.time.Instant.ofEpochMilli(m.joinedAt), java.time.ZoneId.systemDefault())
            .toString
        )
      val rows = members.map { m =>
        val isOwner = m.userId == team.ownerId
        val ownerPill = if (isOwner) " " + Ui.pill("Owner", "bg-brand") else ""
        val remove =
          if (manage && !isOwner)
            s"""<form method="post" action="/teams/${team.id}/members/${m.userId}/remove" class="nowrap"
                 data-confirm="Remove ${Ui.esc(m.displayName)} from this team?">
                 ${Ui.csrf(csrf)}
                 <button class="btn btn-xs btn-ghost-danger" type="submit">Remove</button>
               </form>"""
          else if (isOwner) s"""<span class="small muted">team owner</span>"""
          else ""
        s"""<tr>
             <td><span class="avatar-row">${Ui.avatarSm(m.displayName, Some(m.displayName))} ${Ui.esc(m.displayName)}$ownerPill</span></td>
             <td>${Ui.esc(m.email)}</td>
             <td class="nowrap sub">${joinedDate(m)}</td>
             <td class="td-right">$remove</td>
           </tr>"""
      }.mkString("\n")
      s"""<div class="tablewrap"><table class="grid">
           <thead><tr><th>Member</th><th>Email</th><th>Joined</th><th></th></tr></thead>
           <tbody>$rows</tbody>
         </table></div>"""
    }
  }

  private def projectRows(ctx: Web.Ctx, env: Env, teamId: Long): IO[List[pm.domain.ProjectListRow]] =
    env.projects.listRows(ctx.user.id, ctx.user.isAdmin).map(_.filter(_.project.teamId == teamId))

  private def detailView(
      ctx: Web.Ctx,
      team: Team,
      manage: Boolean,
      members: List[MemberRow],
      projects: List[pm.domain.ProjectListRow],
      errors: Map[String, String],
      values: Map[String, String]
  ): String = {
    val flash = ctx.flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")
    val errFlash = errors.get("_form").map(f => s"""<div class="flash bad">${Ui.esc(f)}</div>""").getOrElse("")
    val archivedBanner =
      if (team.archived)
        """<div class="alert warn">This team is archived. Its projects and tasks are kept for reference but the team is excluded from dashboard totals.</div>"""
      else ""

    val memberCount = members.size
    val activeProjects = projects.count(_.project.status == pm.domain.Project.StatusActive)
    val totalTasks = projects.map(_.taskCount).sum

    val manageActions =
      if (manage)
        s"""<div class="pagehead-actions">
             <a class="btn btn-outline" href="#edit-team">Edit</a>
             <form method="post" action="/teams/${team.id}/${if (team.archived) "restore" else "archive"}" class="nowrap"
                   data-confirm="${if (team.archived) "Restore this team to active use?" else "Archive this team? Projects and tasks are kept."}">
               ${Ui.csrf(ctx.csrf)}
               <button class="btn btn-secondary" type="submit">${if (team.archived) "Restore" else "Archive"}</button>
             </form>
             <form method="post" action="/teams/${team.id}/delete" class="nowrap"
                   data-confirm="Permanently delete this team? This cannot be undone.">
               ${Ui.csrf(ctx.csrf)}
               <button class="btn btn-danger" type="submit">Delete…</button>
             </form>
           </div>"""
      else """<div class="pagehead-actions"></div>"""

    val header =
      s"""<div class="pagehead">
           <div>
             <div class="crumbs"><a href="/app">Dashboard</a><span class="breadcrumb-sep">/</span><a href="/teams">Teams</a><span class="breadcrumb-sep">/</span>${Ui.esc(team.name)}</div>
             <h1>${Ui.esc(team.name)} ${if (team.archived) Ui.archivedPill() else ""}</h1>
             <p class="sub">${Ui.esc(team.description)}</p>
           </div>
           $manageActions
         </div>"""

    val stats =
      s"""<div class="statgrid">
           ${Ui.statCard("Members", memberCount.toString, "people in this workspace", "◉", "alt")}
           ${Ui.statCard("Active projects", activeProjects.toString, s"$totalTasks tasks total", "▤")}
           ${Ui.statCard("Archived projects", (projects.size - activeProjects).toString, "kept for reference", "◷", "alt2")}
         </div>"""

    val projectsTable =
      if (projects.isEmpty)
        Ui.emptyState(
          "No projects yet",
          "Projects hold the task board for this workspace. Create the first one to get going.",
          Some(s"""<a class="btn btn-primary" href="/projects">Create a project</a>""")
        )
      else {
        val rows = projects.map { pr =>
          val p = pr.project
          val status = if (p.status == pm.domain.Project.StatusArchived) Ui.archivedPill() else Ui.pill("Active", "bg-brand")
          val pct = if (pr.taskCount == 0) 0 else math.round(pr.doneCount.toDouble * 100 / pr.taskCount).toInt
          s"""<tr>
               <td><a class="linkish" href="/projects/${p.id}">${Ui.esc(p.name)}</a>
                   <div class="sub">${pr.doneCount}/${pr.taskCount} tasks done</div></td>
               <td>$status</td>
               <td class="td-num">$pct%</td>
             </tr>"""
        }.mkString("\n")
        s"""<div class="tablewrap"><table class="grid">
             <thead><tr><th>Project</th><th>Status</th><th class="num">Done</th></tr></thead>
             <tbody>$rows</tbody>
           </table></div>"""
      }

    val addMemberForm =
      s"""<div class="surface sec" id="add-member">
           <div class="sec-head"><h2>Add a member</h2></div>
           <div class="sec-body">
             <form method="post" action="/teams/${team.id}/members/add" novalidate class="formrow">
               ${Ui.csrf(ctx.csrf)}
               <div class="field" >
                 <label class="flabel" for="memberEmail">Email</label>
                 <input class="input" type="email" id="memberEmail" name="email"
                        placeholder="teammate@company.com" autocomplete="off"
                        ${errors.get("memberEmail").fold("")(_ => """ aria-invalid="true"""")}
                        aria-describedby="memberEmail-err" >
                 ${errors.get("memberEmail").map(e => s"""<p class="ferr" role="alert">${Ui.esc(e)}</p>""").getOrElse("")}
               </div>
               ${Ui.submit("addMember", "Add member", "btn-secondary")}
             </form>
           </div>
         </div>"""

    val editForm =
      s"""<div class="surface sec" id="edit-team">
           <div class="sec-head"><h2>Edit team</h2></div>
           <div class="sec-body">
             <form method="post" action="/teams/${team.id}/edit" novalidate>
               ${Ui.csrf(ctx.csrf)}
               ${Ui.textField("name", "Team name", values.getOrElse("name", team.name), errors.get("name"), required = true, maxlength = Some(160))}
               ${Ui.textareaField("description", "Description", values.getOrElse("description", team.description), errors.get("description"), rows = 3, maxlength = Some(2000))}
               ${Ui.submit("editTeam", "Save changes", "btn-brand", Some("Saving…"))}
             </form>
           </div>
         </div>"""

    val membersPanel =
      s"""<div class="surface sec">
           <div class="sec-head"><h2>Members <span class="sub">($memberCount)</span></h2></div>
           <div class="sec-body no-pad">${memberTable(ctx.csrf, team, members, manage)}</div>
           ${if (manage) s"""<div class="sec-body">$addMemberForm</div>""" else ""}
         </div>"""

    val projectsPanel =
      s"""<div class="surface sec">
           <div class="sec-head"><h2>Projects</h2><a class="small" href="/projects">Manage →</a></div>
           <div class="sec-body no-pad">$projectsTable</div>
         </div>"""

    val managePanel =
      if (manage) editForm else ""

    val body = flash + errFlash + archivedBanner + header + stats +
      s"""<div class="grid-2">
           <div>$projectsPanel$managePanel</div>
           <div>$membersPanel</div>
         </div>"""
    Layout.shell(ctx, "teams", team.name, body)
  }

  private def detail(ctx: Web.Ctx, env: Env, id: Long, errors: Map[String, String] = Map.empty, values: Map[String, String] = Map.empty): IO[Response[IO]] =
    env.teams.getById(id).flatMap {
      case None => Pages.notFound(ctx, "This team does not exist or was deleted.")
      case Some(team) =>
        val manage = ctx.user.isAdmin || team.ownerId == ctx.user.id
        val visible = manage || ctx.user.isAdmin
        if (!visible && team.ownerId != ctx.user.id)
          env.teams.isMember(id, ctx.user.id).flatMap {
            case false => Pages.forbidden(ctx, "You are not a member of this team.")
            case true  => renderDetail(ctx, env, team, manage, errors, values)
          }
        else renderDetail(ctx, env, team, manage, errors, values)
    }

  private def renderDetail(ctx: Web.Ctx, env: Env, team: Team, manage: Boolean, errors: Map[String, String], values: Map[String, String]): IO[Response[IO]] =
    for {
      members <- env.teams.members(team.id)
      projects <- projectRows(ctx, env, team.id)
      page = detailView(ctx, team, manage, members, projects, errors, values)
      resp <- ctx.html(page)
    } yield resp

  private def edit(ctx: Web.Ctx, env: Env, req: Request[IO], id: Long): IO[Response[IO]] =
    env.teams.getById(id).flatMap {
      case None => Pages.notFound(ctx, "This team does not exist.")
      case Some(team) =>
        val canManage = ctx.user.isAdmin || team.ownerId == ctx.user.id
        if (!canManage) Pages.forbidden(ctx, "Only the team owner or an admin can edit this team.")
        else
          Web.formParams(req).flatMap { p =>
            val ok = postCsrfOk(ctx, p, csrfHeader(req))
            val name = p.getOrElse("name", "").trim
            val desc = p.getOrElse("description", "").trim
            var errors = Map.empty[String, String]
            validName(name).foreach(e => errors += "name" -> e)
            validDesc(desc).foreach(e => errors += "description" -> e)
            if (!ok) errors += "_form" -> "Your session expired — please try again."
            if (errors.nonEmpty)
              detail(ctx, env, id, errors, Map("name" -> name, "description" -> desc))
            else
              env.teams.update(id, name, desc).flatMap { _ =>
                ctx.redirect(s"/teams/$id", Some("Team details saved."))
              }
          }
    }

  private def setArchived(ctx: Web.Ctx, env: Env, id: Long, archived: Boolean): IO[Response[IO]] =
    env.teams.getById(id).flatMap {
      case None => Pages.notFound(ctx, "This team does not exist.")
      case Some(team) =>
        val canManage = ctx.user.isAdmin || team.ownerId == ctx.user.id
        if (!canManage) Pages.forbidden(ctx, "Only the team owner or an admin can change this team.")
        else
          env.teams.setArchived(id, archived).flatMap { _ =>
            ctx.redirect(s"/teams/$id", Some(if (archived) "Team archived." else "Team restored to active."))
          }
    }

  private def deleteTeam(ctx: Web.Ctx, env: Env, id: Long): IO[Response[IO]] =
    env.teams.getById(id).flatMap {
      case None => Pages.notFound(ctx, "This team does not exist.")
      case Some(team) =>
        val canManage = ctx.user.isAdmin || team.ownerId == ctx.user.id
        if (!canManage) Pages.forbidden(ctx, "Only the team owner or an admin can delete this team.")
        else
          for {
            members <- env.teams.members(id)
            projects <- env.projects.listRows(ctx.user.id, ctx.user.isAdmin).map(_.filter(_.project.teamId == id))
            resp <-
              if (projects.nonEmpty)
                ctx.redirect(s"/teams/$id", Some("Cannot delete: this team still has projects. Archive the team instead."))
              else if (members.exists(_.userId != ctx.user.id))
                ctx.redirect(s"/teams/$id", Some("Cannot delete: remove other members first (only an empty team can be deleted)."))
              else
                env.teams.delete(id).flatMap(_ => ctx.redirect("/teams", Some(s"Team “${team.name}” deleted.")))
          } yield resp
    }

  private def addMember(ctx: Web.Ctx, env: Env, req: Request[IO], id: Long): IO[Response[IO]] =
    env.teams.getById(id).flatMap {
      case None => Pages.notFound(ctx, "This team does not exist.")
      case Some(team) =>
        val canManage = ctx.user.isAdmin || team.ownerId == ctx.user.id
        if (!canManage) Pages.forbidden(ctx, "Only the team owner or an admin can manage membership.")
        else
          Web.formParams(req).flatMap { p =>
            val ok = postCsrfOk(ctx, p, csrfHeader(req))
            val email = p.getOrElse("email", "").trim.toLowerCase
            val add: IO[Response[IO]] =
              if (email.isEmpty) detail(ctx, env, id, Map("memberEmail" -> "Enter the email of an existing user."), Map.empty)
              else
                env.users.findByEmail(email).flatMap {
                  case None => detail(ctx, env, id, Map("memberEmail" -> "No account found with that email."), Map.empty)
                  case Some(u) if !u.active =>
                    detail(ctx, env, id, Map("memberEmail" -> "That account is inactive and cannot be added."), Map.empty)
                  case Some(u) =>
                    env.teams.isMember(id, u.id).flatMap {
                      case true => detail(ctx, env, id, Map("memberEmail" -> s"${u.displayName} is already a member."), Map.empty)
                      case false =>
                        env.teams.addMember(id, u.id).flatMap { _ =>
                          ctx.redirect(s"/teams/$id", Some(s"${u.displayName} added to the team."))
                        }
                    }
                }
            if (!ok) Pages.forbidden(ctx, "Your session expired — please try again.")
            else add
          }
    }

  private def removeMember(ctx: Web.Ctx, env: Env, id: Long, userId: Long): IO[Response[IO]] =
    env.teams.getById(id).flatMap {
      case None => Pages.notFound(ctx, "This team does not exist.")
      case Some(team) =>
        val canManage = ctx.user.isAdmin || team.ownerId == ctx.user.id
        if (!canManage) Pages.forbidden(ctx, "Only the team owner or an admin can manage membership.")
        else if (team.ownerId == userId)
          ctx.redirect(s"/teams/$id", Some("The team owner cannot be removed."))
        else
          env.teams.removeMember(id, userId).flatMap { n =>
            if (n > 0) ctx.redirect(s"/teams/$id", Some("Member removed from the team."))
            else ctx.redirect(s"/teams/$id", Some("That person is not a member of this team."))
          }
    }

  def apply(env: Env): Web.Ctx => HttpRoutes[IO] = ctx =>
    HttpRoutes.of[IO] {
      case GET -> Root / "teams" =>
        index(ctx, env)
      case req @ POST -> Root / "teams" / "create" =>
        create(ctx, env, req)
      case GET -> Root / "teams" / LongVar(id) =>
        detail(ctx, env, id)
      case req @ POST -> Root / "teams" / LongVar(id) / "edit" =>
        edit(ctx, env, req, id)
      case POST -> Root / "teams" / LongVar(id) / "archive" =>
        setArchived(ctx, env, id, archived = true)
      case POST -> Root / "teams" / LongVar(id) / "restore" =>
        setArchived(ctx, env, id, archived = false)
      case POST -> Root / "teams" / LongVar(id) / "delete" =>
        deleteTeam(ctx, env, id)
      case req @ POST -> Root / "teams" / LongVar(id) / "members" / "add" =>
        addMember(ctx, env, req, id)
      case POST -> Root / "teams" / LongVar(id) / "members" / LongVar(userId) / "remove" =>
        removeMember(ctx, env, id, userId)
    }
}
