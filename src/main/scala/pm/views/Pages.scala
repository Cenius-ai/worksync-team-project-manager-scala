package pm.views

import cats.effect.IO
import org.http4s.{Response, Status}
import pm.http.Web
import pm.http.Web.Ctx

/** Styled 403 / 404 / 500 pages inside the app shell. */
object Pages {
  private def page(ctx: Ctx, status: Status, code: String, heading: String, message: String, navKey: String, title: String): IO[Response[IO]] = {
    val tone = if (status == Status.Forbidden || status == Status.InternalServerError) " bad" else ""
    val body =
      s"""<div class="error-page">
           <div class="code$tone">$code</div>
           <h1>${Ui.esc(heading)}</h1>
           <p class="muted">${Ui.esc(message)}</p>
           <p><a class="btn btn-primary" href="/app">Back to dashboard</a></p>
         </div>"""
    ctx.html(Layout.shell(ctx, navKey, title, body), status)
  }

  def forbidden(ctx: Ctx, message: String, navKey: String = "dashboard"): IO[Response[IO]] =
    page(ctx, Status.Forbidden, "403", "Forbidden", message, navKey, "Forbidden")

  def notFound(ctx: Ctx, message: String, navKey: String = "dashboard"): IO[Response[IO]] =
    page(ctx, Status.NotFound, "404", "Not found", message, navKey, "Not found")

  def serverError(ctx: Ctx): IO[Response[IO]] =
    page(
      ctx,
      Status.InternalServerError,
      "500",
      "Something went wrong",
      "An unexpected error occurred. Please try again — your data is safe.",
      "dashboard",
      "Error"
    )
}
