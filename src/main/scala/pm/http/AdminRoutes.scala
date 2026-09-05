package pm.http

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import pm.Env
import pm.Util
import pm.domain.User
import pm.views.{Layout, Pages, Ui}

/** /admin/users — admin-only user management with role + active toggles. */
object AdminRoutes {
  private def csrfHeader(req: Request[IO]): Option[String] =
    req.headers.get(org.typelevel.ci.CIString("X-CSRF-Token")).map(_.head.value)

  private def view(ctx: Web.Ctx, users: List[User], flash: Option[String]): String = {
    val fl = flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")
    val trs = users.map { u =>
      val isMe = u.id == ctx.user.id
      val roleCell =
        if (isMe) Ui.rolePill(u.role) + """ <span class="small muted">(you)</span>"""
        else Ui.rolePill(u.role)
      val roleForm =
        if (isMe) ""
        else
          s"""<form method="post" action="/admin/users/${u.id}/role" class="nowrap formrow" style="gap:4px">
               ${Ui.csrf(ctx.csrf)}
               <select class="input" name="role" aria-label="Role for ${Ui.esc(u.displayName)}" style="width:auto;padding:4px 6px;font-size:12px">
                 <option value="Member" ${if (u.role == User.RoleMember) "selected" else ""}>Member</option>
                 <option value="Admin" ${if (u.role == User.RoleAdmin) "selected" else ""}>Admin</option>
               </select>
               <button class="btn btn-xs btn-secondary" type="submit" data-submit-label="Saving…">Set</button>
             </form>"""
      val toggle =
        if (isMe) """<span class="small muted">cannot change own account</span>"""
        else
          s"""<form method="post" action="/admin/users/${u.id}/toggle" class="nowrap">
               ${Ui.csrf(ctx.csrf)}
               <button class="btn btn-xs ${if (u.active) "btn-ghost-danger" else "btn-secondary"}" type="submit"
                       data-confirm="${if (u.active) s"Deactivate ${Ui.esc(u.displayName)}? They will no longer be able to sign in." else s"Re-activate ${Ui.esc(u.displayName)}?"}">
                 ${if (u.active) "Deactivate" else "Activate"}
               </button>
             </form>"""
      val created = Util.humanDate(java.time.LocalDate.ofEpochDay(u.createdAt / 86400000L).toString)
      s"""<tr data-filter-value="${Ui.esc(u.email + " " + u.displayName + " " + u.role).toLowerCase}">
           <td>${Ui.esc(u.email)}</td>
           <td><span class="avatar-row">${Ui.avatarSm(u.displayName, Some(u.displayName))} ${Ui.esc(u.displayName)}</span></td>
           <td>$roleCell</td>
           <td>${Ui.activePill(u.active)}</td>
           <td class="nowrap sub">$created</td>
           <td class="td-right"><div class="row-actions">$roleForm$toggle</div></td>
         </tr>"""
    }.mkString("\n")

    val header =
      s"""<div class="pagehead">
           <div>
             <div class="crumbs"><a href="/app">Dashboard</a><span class="breadcrumb-sep">/</span>Admin</div>
             <h1>Users</h1>
             <p class="sub">${users.size} accounts · manage roles and access</p>
           </div>
         </div>"""
    val body =
      fl + header +
        s"""<div class="filtermode">
             <span class="fm-label">Accounts</span>
             <input class="input" type="search" data-list-filter data-list-scope="#user-table" placeholder="Filter by name, email or role…" aria-label="Filter users" style="width:260px">
           </div>
           <div class="tablewrap" id="user-table"><div class="table-scroll">
             <table class="grid">
               <thead><tr><th>Email</th><th>Name</th><th>Role</th><th>Status</th><th>Created</th><th class="td-right">Actions</th></tr></thead>
               <tbody>$trs</tbody>
             </table>
             <div data-filter-empty class="empty" style="display:none"><p>No users match that filter.</p></div>
           </div></div>"""
    Layout.shell(ctx, "admin", "Admin · Users", body)
  }

  private def usersPage(ctx: Web.Ctx, env: Env): IO[Response[IO]] =
    env.users.listAll.flatMap(users => ctx.html(view(ctx, users, ctx.flash)))

  private def guardAdmin(ctx: Web.Ctx)(f: => IO[Response[IO]]): IO[Response[IO]] =
    if (ctx.user.isAdmin) f
    else
      Pages.forbidden(
        ctx,
        "Admin access required. Your account is a Member — ask an admin if you need elevated access.",
        "dashboard"
      )

  private def activeAdminCount(env: Env): IO[Long] =
    env.users.listAll.map(_.count(u => u.active && u.role == User.RoleAdmin))

  private def changeRole(ctx: Web.Ctx, env: Env, req: Request[IO], userId: Long): IO[Response[IO]] =
    guardAdmin(ctx) {
      Web.formParams(req).flatMap { p =>
        val ok = Web.csrfOk(p, csrfHeader(req), ctx.csrf)
        val role = p.getOrElse("role", "")
        if (!ok) Pages.forbidden(ctx, "Your session expired — please try again.")
        else if (role != User.RoleAdmin && role != User.RoleMember) usersPage(ctx, env)
        else
          env.users.findById(userId).flatMap {
            case None => usersPage(ctx, env)
            case Some(target) =>
              if (target.id == ctx.user.id && role != User.RoleAdmin)
                ctx.redirect("/admin/users", Some("You cannot demote your own account."))
              else if (target.role == User.RoleAdmin && role != User.RoleAdmin)
                activeAdminCount(env).flatMap { count =>
                  if (count <= 1)
                    ctx.redirect("/admin/users", Some("Cannot demote: at least one active admin is required."))
                  else
                    env.users.updateRole(userId, role).flatMap { _ =>
                      ctx.redirect("/admin/users", Some(s"${target.displayName} is now a Member."))
                    }
                }
              else
                env.users.updateRole(userId, role).flatMap { _ =>
                  ctx.redirect("/admin/users", Some(s"${target.displayName} is now ${if (role == User.RoleAdmin) "an Admin" else "a Member"}."))
                }
          }
      }
    }

  private def toggle(ctx: Web.Ctx, env: Env, req: Request[IO], userId: Long): IO[Response[IO]] =
    guardAdmin(ctx) {
      Web.formParams(req).flatMap { p =>
        val ok = Web.csrfOk(p, csrfHeader(req), ctx.csrf)
        if (!ok) Pages.forbidden(ctx, "Your session expired — please try again.")
        else
          env.users.findById(userId).flatMap {
            case None => usersPage(ctx, env)
            case Some(target) =>
              if (target.id == ctx.user.id)
                ctx.redirect("/admin/users", Some("You cannot deactivate your own account."))
              else if (target.active && target.role == User.RoleAdmin)
                activeAdminCount(env).flatMap { count =>
                  if (count <= 1)
                    ctx.redirect("/admin/users", Some("Cannot deactivate: at least one active admin is required."))
                  else
                    env.users.setActive(userId, false).flatMap { _ =>
                      ctx.redirect("/admin/users", Some(s"${target.displayName} deactivated."))
                    }
                }
              else
                env.users.setActive(userId, !target.active).flatMap { _ =>
                  ctx.redirect("/admin/users", Some(s"${target.displayName} ${if (target.active) "deactivated" else "activated"}."))
                }
          }
      }
    }

  def apply(env: Env): Web.Ctx => HttpRoutes[IO] = ctx =>
    HttpRoutes.of[IO] {
      case GET -> Root / "admin" / "users" =>
        guardAdmin(ctx) { usersPage(ctx, env) }
      case req @ POST -> Root / "admin" / "users" / LongVar(userId) / "role" =>
        changeRole(ctx, env, req, userId)
      case req @ POST -> Root / "admin" / "users" / LongVar(userId) / "toggle" =>
        toggle(ctx, env, req, userId)
    }
}
