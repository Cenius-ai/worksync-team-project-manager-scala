package pm.http

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import pm.Env
import pm.Util
import pm.domain.{Task, TaskListRow, User}
import pm.views.{Layout, Pages, Ui}

/** GET /app — dashboard with stat cards, an SVG status chart and recent tasks. */
object DashboardRoutes {
  private val StatusColors = Map(
    Task.StatusTodo -> "#55789a",
    Task.StatusInProgress -> "#c07b1c",
    Task.StatusDone -> "#2f9d6d"
  )

  /** Server-side SVG donut built from the SQL aggregate. */
  private def donut(counts: Map[String, Long]): String = {
    val order = List(Task.StatusTodo, Task.StatusInProgress, Task.StatusDone)
    val total = order.map(counts.getOrElse(_, 0L)).sum
    if (total == 0)
      return Ui.emptyState("No tasks yet", "Create a task from any project to start tracking status.")
    val labels = Map(Task.StatusTodo -> "To-do", Task.StatusInProgress -> "In progress", Task.StatusDone -> "Done")
    val r = 15.9155 // circumference = 100 so dasharray = percentage
    val segs = order.zipWithIndex.map { case (s, i) =>
      val c = counts.getOrElse(s, 0L)
      val pct = c.toDouble / total.toDouble * 100.0
      val offset = order.take(i).map(x => counts.getOrElse(x, 0L)).sum.toDouble / total.toDouble * 100.0
      s"""<circle cx="50" cy="50" r="$r" fill="none" stroke="${StatusColors(s)}" stroke-width="7"
           stroke-dasharray="${"%.2f".format(pct)} ${"%.2f".format(100.0 - pct)}"
           stroke-dashoffset="${"%.2f".format(25.0 - offset)}"/>"""
    }.mkString("\n")
    val legend = order.map { s =>
      val c = counts.getOrElse(s, 0L)
      val pct = if (total == 0) 0 else (c.toDouble / total.toDouble * 100).round
      s"""<div class="lg-row">
           <span class="lg-swatch" style="background:${StatusColors(s)}" aria-hidden="true"></span>
           <span>${labels(s)}</span><span class="lg-count">$c · $pct%</span>
         </div>"""
    }.mkString("\n")
    s"""<div style="display:flex;gap:20px;align-items:center;flex-wrap:wrap">
         <svg role="img" aria-label="Task status distribution" viewBox="0 0 100 100" style="width:132px;height:132px;transform:rotate(-90deg)">
           <circle cx="50" cy="50" r="$r" fill="none" stroke="#e8efec" stroke-width="7"/>
           $segs
           <text x="50" y="54" transform="rotate(90 50 50)" text-anchor="middle" fill="#0b1512"
                 font-size="26" font-weight="800" font-family="inherit">$total</text>
         </svg>
         <div class="chart-legend">$legend</div>
       </div>"""
  }

  private def recentTable(rows: List[TaskListRow]): String = {
    if (rows.isEmpty) Ui.emptyState("Nothing tracked yet", "Recent activity across your projects will appear here.")
    else {
      val trs = rows.map { r =>
        val assignee = r.assigneeName.getOrElse("Unassigned")
        val due = r.task.dueDate.map(Util.humanDate).getOrElse("—")
        s"""<tr>
             <td><a class="linkish" href="/tasks/${r.task.id}">${Ui.esc(r.task.title)}</a>
                 <div class="sub">${Ui.esc(r.teamName)} · ${Ui.esc(r.projectName)}</div></td>
             <td>${Ui.esc(assignee)}</td>
             <td class="nowrap">${Ui.esc(due)}</td>
             <td>${Ui.statusPill(r.task.status)}</td>
           </tr>"""
      }.mkString("\n")
      s"""<div class="tablewrap"><div class="table-scroll">
           <table class="grid">
             <thead><tr><th>Task</th><th>Assignee</th><th>Due</th><th>Status</th></tr></thead>
             <tbody>$trs</tbody>
           </table>
         </div></div>"""
    }
  }

  private def view(
      ctx: Web.Ctx,
      stats: (Long, Long, Long, Long),
      dist: List[(String, Long)],
      recent: List[TaskListRow],
      hasTeams: Boolean
  ): String = {
    val u = ctx.user
    val counts = dist.toMap
    val flash = ctx.flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")

    val body = new StringBuilder()
    body.append(flash)
    body.append("""<div class="pagehead">
                    <div>
                      <div class="kicker">Overview</div>
                      <h1>Welcome back, """ + Ui.esc(u.displayName.split(" ").head) + """</h1>
                    </div>
                    <div class="pagehead-actions">""")
    if (hasTeams) {
      body.append("""<a class="btn btn-outline" href="/teams">New team</a>
                      <a class="btn btn-secondary" href="/projects">New project</a>
                      <a class="btn btn-primary" href="/tasks/new">New task</a>""")
    }
    body.append("</div></div>")

    if (!hasTeams) {
      body.append(
        """<div class="surface">""" +
          Ui.emptyState(
            "Start with a team",
            "Worksync organises projects inside team workspaces. Create your first team, add teammates by email, then projects and tasks will follow.",
            Some("""<a class="btn btn-primary" href="/teams">Create your first team</a>""")
          ) +
          "</div>"
      )
    } else {
      body.append(
        s"""<div class="statgrid">
             ${Ui.statCard("Active projects", stats._1.toString, "across your teams", "▤")}
             ${Ui.statCard("Open tasks", stats._2.toString, "still to be done", "☰", "alt")}
             ${Ui.statCard("Assigned to me", stats._3.toString, "open tasks you own", "◆", "alt2")}
             ${Ui.statCard("Logged · 7 days", Util.fmtMinutes(stats._4.toInt), "time entries this week", "◷", "alt3")}
           </div>
           <div class="dashgrid">
             <div class="panel">
               <div class="panel-head"><h2>Task status</h2><span class="sub">${recent.size + " recent tasks shown below"}</span></div>
               <div class="panel-body">${donut(counts)}</div>
             </div>
             <div class="panel">
               <div class="panel-head"><h2>Recent tasks</h2><a class="small" href="/tasks">View all →</a></div>
               <div class="panel-body no-pad">${recentTable(recent)}</div>
             </div>
           </div>"""
      )
    }
    Layout.shell(ctx, "dashboard", "Dashboard", body.toString())
  }

  def apply(env: Env): Web.Ctx => HttpRoutes[IO] = ctx =>
    HttpRoutes.of[IO] {
      case GET -> Root / "app" =>
        val uid = ctx.user.id
        val admin = ctx.user.isAdmin
        (for {
          stats <- env.dashboard.stats(uid, admin)
          dist <- env.dashboard.statusDistribution(uid, admin)
          recent <- env.tasks.recentRows(uid, admin, 6)
          teams <- env.teams.listRows(uid, admin)
          page = view(ctx, stats, dist, recent, teams.nonEmpty)
          resp <- ctx.html(page)
        } yield resp).handleErrorWith { err =>
          org.slf4j.LoggerFactory.getLogger("dashboard").error("dashboard render failed", err)
          Pages.serverError(ctx)
        }
    }
}
