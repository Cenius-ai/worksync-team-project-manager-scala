package pm.http

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.staticcontent.resourceServiceBuilder
import pm.Env
import pm.views.Pages

/** Composes the full application: assets, health, auth pages, protected
  * feature routes (session + role guards) and the styled fallback pages.
  * HttpRoutes is Kleisli[[A] =>> OptionT[IO, A], Request[IO], Response[IO]], so guard
  * routers fall through by producing OptionT.none.
  */
object RouterApp {
  type ORoutes = HttpRoutes[IO]

  private def assetRoutes: ORoutes =
    Router("/assets" -> resourceServiceBuilder[IO]("/public").toRoutes)

  private def health: ORoutes =
    HttpRoutes.of[IO] {
      case GET -> Root / "health" => Ok("ok")
    }

  private def featureRoutes(ctx: Web.Ctx, env: Env): ORoutes =
    DashboardRoutes(env)(ctx) <+>
      TeamRoutes(env)(ctx) <+>
      ProjectRoutes(env)(ctx) <+>
      TaskRoutes(env)(ctx) <+>
      AdminRoutes(env)(ctx)

  /** Session cookie + DB session → per-request context (user, csrf, flash). */
  private def resolveCtx(env: Env, req: Request[IO]): IO[Option[Web.Ctx]] =
    Web.sessionToken(req) match {
      case None => IO.pure(None)
      case Some(token) =>
        env.sessions.findValidUser(token).map {
          case Some((user, session)) =>
            Some(
              Web.Ctx(
                user = user,
                sessionToken = session.token,
                csrf = Web.csrfFor(env.cfg.sessionSecret, session.token),
                flash = Web.flashOf(req),
                req = req
              )
            )
          case None => None
        }
    }

  private def kleisli(f: Request[IO] => OptionT[IO, Response[IO]]): ORoutes =
    Kleisli[[A] =>> OptionT[IO, A], Request[IO], Response[IO]](f)

  /** `/` → dashboard when signed in, otherwise the login page. */
  private def home(env: Env): ORoutes =
    kleisli { req =>
      val isRoot = req.method == Method.GET && req.uri.path.renderString == "/"
      if (!isRoot) OptionT.none
      else
      OptionT.liftF(resolveCtx(env, req)).flatMap {
        case Some(_) => OptionT.liftF(Web.redirect("/app"))
        case None    => OptionT.liftF(Web.redirect("/login"))
      }
    }

  /** Signed-in users hitting login/register go straight to the dashboard;
    * anonymous visitors fall through to the public auth routes.
    */
  private def authPageRedirect(env: Env): ORoutes =
    kleisli { req =>
      val isAuthPage = req.method == Method.GET &&
        (req.uri.path.renderString == "/login" || req.uri.path.renderString == "/register")
      if (!isAuthPage) OptionT.none
      else
        OptionT(resolveCtx(env, req)).flatMap { _ =>
          OptionT.liftF(Web.redirect("/app"))
        }
    }

  /** Authenticated feature routes; unmatched paths fall through to fallback. */
  private def protectedDispatch(env: Env): ORoutes =
    kleisli { req =>
      OptionT(resolveCtx(env, req)).flatMap { ctx =>
        featureRoutes(ctx, env).run(req)
      }
    }

  private def fallback(env: Env): ORoutes =
    kleisli { req =>
      OptionT(resolveCtx(env, req))
        .flatMap { ctx =>
          OptionT.liftF(
            Pages.notFound(
              ctx,
              "The page you requested does not exist or may have been moved.",
              navKeyFromPath(req.uri.path.renderString)
            )
          )
        }
        .orElse(OptionT.liftF(Web.toLogin(req)))
    }

  private def navKeyFromPath(path: String): String =
    if (path.startsWith("/teams")) "teams"
    else if (path.startsWith("/projects")) "projects"
    else if (path.startsWith("/tasks")) "tasks"
    else if (path.startsWith("/admin")) "admin"
    else "dashboard"

  def build(env: Env): HttpApp[IO] = {
    val routes: ORoutes =
      assetRoutes <+>
        health <+>
        home(env) <+>
        authPageRedirect(env) <+>
        AuthRoutes.routes(env) <+>
        protectedDispatch(env) <+>
        fallback(env)

    Kleisli { req =>
      routes
        .orNotFound
        .run(req)
        .map(Web.securityHeaders)
        .handleErrorWith { err =>
          IO {
            System.err.println(s"[500] ${req.method} ${req.uri.path.renderString}")
            err.printStackTrace()
          } *>
            Web.html("Internal Server Error", Status.InternalServerError).map(Web.securityHeaders)
        }
    }
  }
}
