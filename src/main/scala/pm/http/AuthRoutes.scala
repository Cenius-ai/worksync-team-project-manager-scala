package pm.http

import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import pm.Env
import pm.Util
import pm.auth.PasswordHasher
import pm.domain.User
import pm.views.{Layout, Ui}

/** /login, /register, /logout — email/password auth over DB sessions. */
object AuthRoutes {
  private val EmailRe = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  private def demoStrip(): String =
    s"""<div class="demo-strip">
         <div><span class="em">Demo: cenius@cenius.ai / cenius</span><br><span class="small">Plus role demos — preview only; remove before production.</span></div>
         <table>
           <tr><td class="mono">cenius@cenius.ai</td><td class="d mono">cenius</td></tr>
           <tr><td class="mono">admin@worksync.dev</td><td class="d mono">admin123</td></tr>
           <tr><td class="mono">member@worksync.dev</td><td class="d mono">member123</td></tr>
         </table>
       </div>"""

  private def brandHead(): String =
    s"""<div class="ac-head">
         <a class="brand" href="/login"><span class="brand-mark" aria-hidden="true">→</span><span>Worksync</span></a>
         <h1  class="mt-8">Team project manager</h1>
       </div>"""

  private def loginBody(err: Option[String], email: String, anonCsrf: String, flash: Option[String], next: Option[String]): String = {
    val errHtml = err.map(e => s"""<div class="flash bad" role="alert">${Ui.esc(e)}</div>""").getOrElse("")
    val fl = flash.map(f => s"""<div class="flash good">${Ui.esc(f)}</div>""").getOrElse("")
    val nextInput = next.map(n => s"""<input type="hidden" name="next" value="${Ui.esc(n)}">""").getOrElse("")
    s"""<div class="auth-wrap">
         <div class="auth-card">
           ${brandHead()}
           <div class="ac-body">
             <div class="kicker">Sign in to your workspace</div>
             $fl$errHtml
             <form method="post" action="/login" novalidate>
               ${Ui.csrf(anonCsrf)}
               ${Ui.textField("email", "Email", email, required = true, inputType = "email", autocomplete = Some("username"), placeholder = "you@company.com")}
               ${Ui.passwordField("password", "Password", "", autocomplete = "current-password")}
               $nextInput
               ${Ui.submit("login", "Sign in", "btn-primary btn-lg", Some("Signing in…"))}
             </form>
             <p class="mt-8 small">New to Worksync? <a href="/register">Create an account</a></p>
           </div>
           ${demoStrip()}
         </div>
       </div>"""
  }

  private def registerBody(errors: Map[String, String], values: Map[String, String], anonCsrf: String): String = {
    def v(k: String) = values.getOrElse(k, "")
    val fl = errors.get("_form").map(e => s"""<div class="flash bad" role="alert">${Ui.esc(e)}</div>""").getOrElse("")
    s"""<div class="auth-wrap">
         <div class="auth-card">
           ${brandHead()}
           <div class="ac-body">
             <div class="kicker">Create your account</div>
             $fl
             <form method="post" action="/register" novalidate>
               ${Ui.csrf(anonCsrf)}
               ${Ui.textField("displayName", "Display name", v("displayName"), errors.get("displayName"), required = true, placeholder = "Ada Lovelace", maxlength = Some(120))}
               ${Ui.textField("email", "Email", v("email"), errors.get("email"), required = true, inputType = "email", autocomplete = Some("username"), placeholder = "you@company.com")}
               ${Ui.passwordField("password", "Password", "", errors.get("password"), autocomplete = "new-password")}
               ${Ui.passwordField("confirm", "Confirm password", "", errors.get("confirm"), autocomplete = "new-password")}
               <p class="fhelp">New accounts start as <strong>Member</strong>; an admin can promote you later.</p>
               ${Ui.submit("register", "Create account", "btn-primary btn-lg", Some("Creating…"))}
             </form>
             <p class="mt-8 small">Already registered? <a href="/login">Sign in</a></p>
           </div>
           ${demoStrip()}
         </div>
       </div>"""
  }

  def loginPage(err: Option[String], email: String, anonCsrf: String, flash: Option[String], next: Option[String]): String =
    Layout.bare("Sign in", loginBody(err, email, anonCsrf, flash, next))

  def registerPage(errors: Map[String, String], values: Map[String, String], anonCsrf: String): String =
    Layout.bare("Create account", registerBody(errors, values, anonCsrf))

  def routes(env: Env): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "login" =>
      val anonCsrf = Util.randomHex(16)
      val next = Web.safeNext(req.params.get("next"))
      val flash = Web.flashOf(req)
      val page = loginPage(None, "", anonCsrf, flash, next)
      Web.html(page).map(Web.clearFlashCookie).map(_.putHeaders(Web.anonCsrfCookie(anonCsrf)))

    case req @ POST -> Root / "login" =>
      val anonCsrf = Web.anonCsrfOf(req).getOrElse("")
      Web.formParams(req).flatMap { p =>
        val csrfOk = Web.csrfOk(p, None, anonCsrf)
        val email = p.getOrElse("email", "").trim
        val password = p.getOrElse("password", "")
        val next = Web.safeNext(p.get("next").orElse(req.params.get("next")))
        if (!csrfOk) Web.html(loginPage(Some("Your session expired — please try again."), email, anonCsrf, None, next), Status.Forbidden)
        else
          env.users.findByEmail(email).flatMap {
            case Some(u) if u.active && PasswordHasher.verify(password, u.passwordHash) =>
              env.sessions.create(u.id).flatMap { token =>
                val dest = next.getOrElse("/app")
                Web.redirect(dest).map(Web.setSessionCookie(_, token, env.cfg.secureCookies))
              }
            case _ =>
              // one generic message — never reveals whether the email exists
              Web.html(loginPage(Some("Invalid email or password."), email, anonCsrf, None, next), Status.Unauthorized)
          }
      }

    case req @ GET -> Root / "register" =>
      val anonCsrf = Util.randomHex(16)
      Web.html(registerPage(Map.empty, Map.empty, anonCsrf)).map(_.putHeaders(Web.anonCsrfCookie(anonCsrf)))

    case req @ POST -> Root / "register" =>
      val anonCsrf = Web.anonCsrfOf(req).getOrElse("")
      Web.formParams(req).flatMap { p =>
        val csrfOk = Web.csrfOk(p, None, anonCsrf)
        val name = p.getOrElse("displayName", "").trim
        val email = p.getOrElse("email", "").trim.toLowerCase
        val password = p.getOrElse("password", "")
        val confirm = p.getOrElse("confirm", "")
        var errors = Map.empty[String, String]
        if (name.length < 2) errors += "displayName" -> "Please enter your name (at least 2 characters)."
        if (name.length > 120) errors += "displayName" -> "Name must be 120 characters or fewer."
        if (EmailRe.findFirstIn(email).isEmpty) errors += "email" -> "Enter a valid email address."
        if (password.length < 8) errors += "password" -> "Password must be at least 8 characters."
        if (password != confirm) errors += "confirm" -> "Passwords do not match."
        if (!csrfOk) errors += "_form" -> "Your session expired — please try again."

        val checkUnique =
          if (errors.contains("email") || !csrfOk) IO.pure(())
          else
            env.users.findByEmail(email).flatMap {
              case Some(_) =>
                IO { errors += "email" -> "An account with this email already exists." }
              case None => IO.unit
            }

        checkUnique.flatMap { _ =>
          if (errors.nonEmpty)
            Web.html(registerPage(errors, Map("displayName" -> name, "email" -> email), anonCsrf),
              if (csrfOk) Status.BadRequest else Status.Forbidden)
          else
            env.users.insert(email, PasswordHasher.hash(password), name, User.RoleMember, active = true).flatMap { uid =>
              env.sessions.create(uid).flatMap { token =>
                Web.redirect("/app")
                  .map(Web.setSessionCookie(_, token, env.cfg.secureCookies))
                  .map(Web.setFlashCookie(_, s"Welcome to Worksync, ${name.split(" ").head}! Your account is ready."))
              }
            }
        }
      }

    case req @ POST -> Root / "logout" =>
      // Session optional: if a valid one exists we revoke it server-side.
      val token = Web.sessionToken(req).getOrElse("")
      val expected = Web.csrfFor(env.cfg.sessionSecret, token)
      Web.formParams(req).flatMap { p =>
        val ok = Web.csrfOk(p, None, expected)
        (if (ok && token.nonEmpty) env.sessions.delete(token) else IO.pure(0)).flatMap { _ =>
          Web.redirect("/login").map(Web.clearSessionCookie).map(Web.setFlashCookie(_, "You have been signed out."))
        }
      }
  }
}
