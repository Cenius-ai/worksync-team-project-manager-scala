package pm.http

import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.http4s.headers.{Location, `Set-Cookie`}
import org.typelevel.ci.CIString
import pm.Util
import pm.domain.User

/** Shared HTTP plumbing: HTML responses, cookies, flashes, forms, CSRF. */
object Web {
  val SessionCookieName = "ws_session"
  val FlashCookieName   = "ws_flash"
  val CsrfField         = "_csrf"

  implicit val urlFormDecoder: EntityDecoder[IO, UrlForm] = UrlForm.entityDecoder[IO]

  def html(body: String, status: Status = Status.Ok): IO[Response[IO]] =
    IO.pure(
      Response[IO](status)
        .withEntity(body)
        .putHeaders(Header.Raw(CIString("Content-Type"), "text/html; charset=utf-8"))
    )

  def redirect(location: String): IO[Response[IO]] =
    IO.pure(Response[IO](Status.SeeOther).putHeaders(Location(Uri.unsafeFromString(location))))

  /** Only accept in-app redirect targets. */
  def safeNext(raw: Option[String]): Option[String] =
    raw.map(_.trim).filter { p =>
      p.startsWith("/") && !p.startsWith("//") && !p.contains("\r") && !p.contains("\n")
    }

  /** Decode a urlencoded POST body into a plain map (first value per key). */
  def formParams(req: Request[IO]): IO[Map[String, String]] =
    req.attemptAs[UrlForm].value.map {
      case Right(f) =>
        f.values.toList.flatMap { case (k, vs) => vs.headOption.map(k -> _) }.toMap
      case Left(_) => Map.empty[String, String]
    }

  def formParam(params: Map[String, String], key: String): Option[String] =
    params.get(key).map(_.trim).filter(_.nonEmpty)

  /* ------------------- cookies ------------------- */

  private def cookieHeader(name: String, value: String, maxAge: Option[Long], secure: Boolean): `Set-Cookie` =
    `Set-Cookie`(
      ResponseCookie(
        name = name,
        content = value,
        path = Some("/"),
        httpOnly = true,
        secure = secure,
        sameSite = Some(SameSite.Lax),
        maxAge = maxAge
      )
    )

  def setSessionCookie(resp: Response[IO], token: String, secure: Boolean): Response[IO] =
    resp.putHeaders(cookieHeader(SessionCookieName, token, Some(30L * 24 * 3600), secure))

  def clearSessionCookie(resp: Response[IO]): Response[IO] =
    resp.putHeaders(cookieHeader(SessionCookieName, "", Some(0L), secure = false))

  def setFlashCookie(resp: Response[IO], message: String): Response[IO] =
    resp.putHeaders(cookieHeader(FlashCookieName, Util.urlEncode(message), Some(60L), secure = false))

  def clearFlashCookie(resp: Response[IO]): Response[IO] =
    resp.putHeaders(cookieHeader(FlashCookieName, "", Some(0L), secure = false))

  def cookieValue(req: Request[IO], name: String): Option[String] = {
    val cookies = req.headers.get(CIString("Cookie")).toList.flatMap(_.toList).flatMap(_.value.split(";").toList)
    cookies.collectFirst {
      case c if c.trim.startsWith(name + "=") => c.trim.substring(name.length + 1).trim
    }
  }

  def sessionToken(req: Request[IO]): Option[String] =
    cookieValue(req, SessionCookieName)

  def flashOf(req: Request[IO]): Option[String] =
    cookieValue(req, FlashCookieName).flatMap(v => Option(Util.urlDecode(v)).map(_.trim).filter(_.nonEmpty))

  /* ------------------- CSRF ------------------- */

  /** Session-bound CSRF token: deterministic from the session token + secret,
    * which the browser cannot read (HttpOnly). The same derivation is used on
    * every page that renders a form and on every state-changing handler.
    */
  def csrfFor(secret: String, sessionToken: String): String =
    Util.hmacHex(secret, "csrf|" + sessionToken, 32)

  def csrfOk(params: Map[String, String], headerValue: Option[String], expected: String): Boolean = {
    val sent = formParam(params, CsrfField).orElse(headerValue.map(_.trim).filter(_.nonEmpty))
    sent.contains(expected)
  }

  /** Anonymous (pre-login) CSRF: a random token set as a plain cookie when the
    * form is rendered; the posted value must match it.
    */
  def anonCsrfCookie(value: String): `Set-Cookie` =
    cookieHeader("ws_anon_csrf", value, Some(120L), secure = false)

  def anonCsrfOf(req: Request[IO]): Option[String] = cookieValue(req, "ws_anon_csrf")

  /* ------------------- security headers ------------------- */

  def securityHeaders(resp: Response[IO]): Response[IO] =
    resp.putHeaders(
      Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
      Header.Raw(CIString("X-Frame-Options"), "DENY"),
      Header.Raw(CIString("Referrer-Policy"), "strict-origin-when-cross-origin"),
      Header.Raw(
        CIString("Content-Security-Policy"),
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
      ),
      Header.Raw(CIString("X-XSS-Protection"), "0")
    )

  /* ------------------- auth context ------------------- */

  /** Per-request view context handed to authenticated handlers. */
  final case class Ctx(user: User, sessionToken: String, csrf: String, flash: Option[String], req: Request[IO]) {
    def html(body: String, status: Status = Status.Ok): IO[Response[IO]] = {
      val base = Web.html(body, status)
      if (flash.nonEmpty) base.map(Web.clearFlashCookie)
      else base
    }

    def redirect(location: String, flash: Option[String] = None): IO[Response[IO]] =
      flash match {
        case Some(msg) => Web.redirect(location).map(Web.setFlashCookie(_, msg))
        case None      => Web.redirect(location)
      }
  }

  def isSafeMethod(m: Method): Boolean =
    m == Method.GET || m == Method.HEAD || m == Method.OPTIONS

  /** Render the generic page that anonymous visitors see when hitting a
    * protected route: redirect to login, remembering where they wanted to go.
    */
  def toLogin(req: Request[IO]): IO[Response[IO]] = {
    val target = req.uri.path.renderString
    val next = if (target == "/" || target == "/app" || target.isEmpty) "" else s"?next=${Util.urlEncode(target)}"
    Web.redirect("/login" + next)
  }
}
