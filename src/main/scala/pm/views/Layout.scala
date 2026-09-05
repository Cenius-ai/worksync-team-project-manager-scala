package pm.views

import pm.http.Web
import pm.http.Web.Ctx
import pm.Util

/** Shared server-rendered app shell: 56px top bar with inline primary nav,
  * role-aware links, a global task search and the signed-in user chip.
  */
object Layout {
  private def navItem(href: String, key: String, label: String, active: String, adminOnly: Boolean = false, cls: String = ""): Option[String] = {
    val isActive = if (active == key) " active" else ""
    val adminCls = if (adminOnly) " nav-admin" else ""
    val svg = key match {
      case "dashboard" => "◆"
      case "teams"     => "▦"
      case "projects"  => "▤"
      case "tasks"     => "☰"
      case "admin"     => "⚙"
      case _           => "→"
    }
    Some(s"""<a class="$cls$isActive$adminCls" href="$href">$svg <span>$label</span></a>""")
  }

  private def nav(user: pm.domain.User, active: String): String = {
    val base = List(
      navItem("/app", "dashboard", "Dashboard", active),
      navItem("/teams", "teams", "Teams", active),
      navItem("/projects", "projects", "Projects", active),
      navItem("/tasks", "tasks", "Tasks", active),
      navItem("/admin/users", "admin", "Admin", active, adminOnly = user.isAdmin)
    ).flatten.mkString("\n")
    s"""<nav class="topnav" aria-label="Primary">$base</nav>"""
  }

  private def userArea(ctx: Ctx): String = {
    val u = ctx.user
    val role = if (u.isAdmin) "Admin" else "Member"
    s"""<div class="userchip">
         <div class="user-meta"><div class="uname">${Util.esc(u.displayName)}</div>
           <div class="urole">${Util.esc(u.email)} · $role</div></div>
         ${Ui.avatar(u.displayName)}
         <form method="post" action="/logout" class="nowrap" data-confirm="Sign out of Worksync?">
           ${Ui.csrf(ctx.csrf)}
           <button type="submit" class="btn btn-sm btn-outline" data-submit-label="Signing out…"
                  
                   title="Sign out">Sign out</button>
         </form>
       </div>"""
  }

  private def searchBox(q: String): String =
    s"""<form class="top-search" role="search" action="/tasks" method="get">
         <input type="search" name="q" value="${Util.esc(q)}" placeholder="Search tasks…"
                aria-label="Search tasks" autocomplete="off">
         <button type="submit" aria-label="Search">Go</button>
       </form>"""

  def shell(ctx: Ctx, active: String, title: String, body: String, q: String = ""): String = {
    val brand =
      s"""<a class="brand" href="/app"><span class="brand-mark" aria-hidden="true">→</span>
          <span>Worksync</span></a>"""
    s"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${Util.esc(title)} · Worksync</title>
  <link rel="icon" href="/assets/favicon.svg" type="image/svg+xml">
  <link rel="stylesheet" href="/assets/styles/app.css">
</head>
<body>
  <a class="skip-link" href="#main">Skip to content</a>
  <header class="topbar">
    $brand
    ${nav(ctx.user, active)}
    <div class="spacer"></div>
    ${searchBox(q)}
    ${userArea(ctx)}
  </header>
  <main id="main" class="canvas">
    $body
  </main>
  <script src="/assets/js/app.js" defer></script>
  <script src="/assets/vendor/alpine.min.js" defer></script>
</body>
</html>"""
  }

  /** Plain document (no chrome) — used by login/register and error pages. */
  def bare(title: String, body: String, extraHead: String = ""): String =
    s"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${Util.esc(title)} · Worksync</title>
  <link rel="icon" href="/assets/favicon.svg" type="image/svg+xml">
  <link rel="stylesheet" href="/assets/styles/app.css">
  $extraHead
</head>
<body>
  $body
  <script src="/assets/js/app.js" defer></script>
  <script src="/assets/vendor/alpine.min.js" defer></script>
</body>
</html>"""
}
