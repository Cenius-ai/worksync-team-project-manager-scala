package pm.views

import pm.Util
import pm.domain._

/** Design-system atoms: every fragment reads only CSS tokens and every
  * user-controlled value passes through Util.esc.
  */
object Ui {
  def esc(s: String): String = Util.esc(s)
  def escOpt(s: Option[String]): String = Util.escOpt(s)

  def initials(name: String): String = Util.initials(name)
  def fmtMinutes(total: Int): String = Util.fmtMinutes(total)

  /* ---------- pills / badges ---------- */

  def pill(text: String, tone: String): String =
    s"""<span class="pill $tone">${esc(text)}</span>"""

  def statusPill(status: String): String = {
    val (tone, label) = status match {
      case Task.StatusTodo       => ("todo", "To-do")
      case Task.StatusInProgress => ("progress", "In progress")
      case Task.StatusDone       => ("done", "Done")
      case other                 => ("bg-soft", other)
    }
    pill(label, tone)
  }

  def priorityPill(priority: String): String = {
    val tone = priority match {
      case Task.PriorityLow    => "low"
      case Task.PriorityMedium => "medium"
      case Task.PriorityHigh   => "high"
      case _                   => "bg-soft"
    }
    pill(priority, tone)
  }

  def rolePill(role: String): String =
    if (role == User.RoleAdmin) pill("Admin", "bg-brand") else pill("Member", "bg-soft")

  def activePill(active: Boolean): String =
    if (active) pill("Active", "bg-brand") else pill("Inactive", "bg-danger")

  def archivedPill(): String = pill("Archived", "bg-warn")

  /* ---------- avatars ---------- */

  def avatar(name: String): String =
    s"""<span class="avatar" aria-hidden="true">${esc(initials(name))}</span>"""

  def avatarSm(name: String, title: Option[String] = None): String = {
    val t = title.map(x => s""" title="${esc(x)}"""").getOrElse("")
    s"""<span class="avatar-sm"$t aria-hidden="true">${esc(initials(name))}</span>"""
  }

  def avatarRow(name: String, sub: Option[String] = None): String = {
    val subHtml = sub.map(s => s"""<span class="sub">${esc(s)}</span>""").getOrElse("")
    s"""<span class="avatar-row">${avatarSm(name)} <span>${esc(name)}</span> $subHtml</span>"""
  }

  /* ---------- empty / info states ---------- */

  def emptyState(title: String, body: String, action: Option[String] = None): String = {
    val a = action.map(h => s"""<div class="mt-8">$h</div>""").getOrElse("")
    s"""<div class="empty"><div class="e-glyph" aria-hidden="true">→</div>
        <h3>${esc(title)}</h3><p>${esc(body)}</p>$a</div>"""
  }

  /* ---------- stat card ---------- */

  def statCard(kicker: String, value: String, note: String, glyph: String, tone: String = ""): String =
    s"""<div class="statcard">
         <div class="k"><span class="glyph $tone" aria-hidden="true">$glyph</span>$kicker</div>
         <div class="v">$value</div>
         <div class="d">${esc(note)}</div>
       </div>"""

  /* ---------- form atoms ---------- */

  def errHtml(error: Option[String]): String = error match {
    case Some(e) => s"""<p class="ferr" role="alert">${esc(e)}</p>"""
    case None    => ""
  }

  def invalidAttr(error: Option[String]): String =
    error.fold("")(_ => """ aria-invalid="true"""")

  def describedBy(error: Option[String], id: String): String =
    error.fold("")(_ => s""" aria-describedby="$id"""")

  def textField(
      name: String,
      label: String,
      value: String,
      error: Option[String] = None,
      required: Boolean = false,
      placeholder: String = "",
      help: Option[String] = None,
      inputType: String = "text",
      autocomplete: Option[String] = None,
      maxlength: Option[Int] = None
  ): String = {
    val req = if (required) s""" <span class="freq" aria-hidden="true">*</span>""" else ""
    val ac = autocomplete.map(a => s""" autocomplete="$a"""").getOrElse("")
    val ml = maxlength.map(m => s""" maxlength="$m"""").getOrElse("")
    val ph = if (placeholder.nonEmpty) s""" placeholder="${esc(placeholder)}"""" else ""
    val helpHtml = help.map(h => s"""<p class="fhelp">${esc(h)}</p>""").getOrElse("")
    val eid = name + "-err"
    s"""<div class="field">
         <label class="flabel" for="$name">${esc(label)}$req</label>
         <input class="input${error.fold("")(_ => " invalid")}" type="$inputType" id="$name" name="$name"
                value="${esc(value)}"$ph$ac$ml${invalidAttr(error)}${describedBy(error, eid)}>
         $helpHtml${errHtml(error)}
       </div>"""
  }

  def textareaField(
      name: String,
      label: String,
      value: String,
      error: Option[String] = None,
      required: Boolean = false,
      rows: Int = 4,
      maxlength: Option[Int] = None,
      help: Option[String] = None
  ): String = {
    val req = if (required) s""" <span class="freq" aria-hidden="true">*</span>""" else ""
    val ml = maxlength.map(m => s""" maxlength="$m"""").getOrElse("")
    val helpHtml = help.map(h => s"""<p class="fhelp">${esc(h)}</p>""").getOrElse("")
    val eid = name + "-err"
    s"""<div class="field">
         <label class="flabel" for="$name">${esc(label)}$req</label>
         <textarea class="input${error.fold("")(_ => " invalid")}" id="$name" name="$name" rows="$rows"$ml${invalidAttr(error)}${describedBy(error, eid)}>${esc(value)}</textarea>
         $helpHtml${errHtml(error)}
       </div>"""
  }

  def selectField(
      name: String,
      label: String,
      options: List[(String, String)],
      selected: Option[String],
      error: Option[String] = None,
      required: Boolean = false,
      includeEmpty: Option[String] = None
  ): String = {
    val req = if (required) s""" <span class="freq" aria-hidden="true">*</span>""" else ""
    val empty = includeEmpty match {
      case Some(txt) => s"""<option value="">${esc(txt)}</option>"""
      case None      => ""
    }
    val opts = options
      .map { case (v, l) =>
        val sel = if (selected.contains(v)) " selected" else ""
        s"""<option value="${esc(v)}"$sel>${esc(l)}</option>"""
      }
      .mkString("\n")
    val eid = name + "-err"
    s"""<div class="field">
         <label class="flabel" for="$name">${esc(label)}$req</label>
         <select class="input${error.fold("")(_ => " invalid")}" id="$name" name="$name"${invalidAttr(error)}${describedBy(error, eid)}>
           $empty$opts
         </select>
         ${errHtml(error)}
       </div>"""
  }

  def dateField(name: String, label: String, value: Option[String], error: Option[String] = None, required: Boolean = false): String =
    textField(name, label, value.getOrElse(""), error, required, inputType = "date")

  def numberField(
      name: String,
      label: String,
      value: Option[String],
      error: Option[String] = None,
      required: Boolean = false,
      min: Option[Int] = None,
      max: Option[Int] = None,
      help: Option[String] = None
  ): String = {
    val mn = min.map(m => s""" min="$m"""").getOrElse("")
    val mx = max.map(m => s""" max="$m"""").getOrElse("")
    val req = if (required) s""" <span class="freq" aria-hidden="true">*</span>""" else ""
    val helpHtml = help.map(h => s"""<p class="fhelp">${esc(h)}</p>""").getOrElse("")
    val eid = name + "-err"
    s"""<div class="field">
         <label class="flabel" for="$name">${esc(label)}$req</label>
         <input class="input${error.fold("")(_ => " invalid")}" type="number" inputmode="numeric" id="$name" name="$name"
                value="${esc(value.getOrElse(""))}"$mn$mx${invalidAttr(error)}${describedBy(error, eid)}>
         $helpHtml${errHtml(error)}
       </div>"""
  }

  def passwordField(name: String, label: String, value: String, error: Option[String] = None, autocomplete: String = "current-password"): String = {
    val eid = name + "-err"
    s"""<div class="field">
         <label class="flabel" for="$name">${esc(label)} <span class="freq" aria-hidden="true">*</span></label>
         <div class="pwwrap">
           <input class="input${error.fold("")(_ => " invalid")}" type="password" id="$name" name="$name"
                  value="${esc(value)}" autocomplete="$autocomplete"${invalidAttr(error)}${describedBy(error, eid)}>
           <button type="button" class="pw-wrap-btn" data-pw-toggle aria-label="Show or hide password">Show</button>
         </div>
         ${errHtml(error)}
       </div>"""
  }

  def submit(name: String, label: String, variant: String = "btn-primary", submitLabel: Option[String] = None, extra: String = ""): String = {
    val sl = submitLabel.map(l => s""" data-submit-label="${esc(l)}"""").getOrElse("")
    s"""<button type="submit" class="btn $variant"$sl$extra>$label</button>"""
  }

  def csrf(token: String): String =
    s"""<input type="hidden" name="${pm.http.Web.CsrfField}" value="${esc(token)}">"""

  def panel(heading: String, body: String, actions: String = ""): String =
    s"""<section class="surface sec">
         <div class="sec-head"><h2>${esc(heading)}</h2>$actions</div>
         <div class="sec-body">$body</div>
       </section>"""

  def kv(rows: List[(String, String)]): String = {
    val body = rows.map { case (k, v) => s"""<dt>${esc(k)}</dt><dd>$v</dd>""" }.mkString
    s"""<dl class="kv">$body</dl>"""
  }
}
