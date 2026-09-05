package pm

import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import pm.db.Database
import pm.http.RouterApp

/** Worksync — Scala 3 + http4s server-rendered team project manager.
  *
  * Boot path (born-bootable, no env required): create the data dir, open the
  * embedded database, apply versioned migrations, seed demo data if empty,
  * print the persistence sanity line, then serve on 0.0.0.0:$PORT (8080).
  */
object Server extends IOApp.Simple {
  def run: IO[Unit] = {
    val cfg = Config.fromEnv
    for {
      _ <- IO(new java.io.File("data").mkdirs())
      _ <- Database.transactor(cfg).use { xa =>
        val env = new Env(cfg, xa)
        for {
          _ <- env.migrations.run()
          _ <- env.seeder.seedIfEmpty()
          _ <- env.seeder.logCounts()
          _ <- env.sessions.deleteExpired()
          _ <- IO.println(s"[boot] migrations applied; sessions pruned")
          port = Port.fromInt(cfg.port).getOrElse(Port.fromInt(8080).get)
          _ <- EmberServerBuilder
            .default[IO]
            .withHost(ipv4"0.0.0.0")
            .withPort(port)
            .withHttpApp(RouterApp.build(env))
            .build
            .use { server =>
              IO.println(s"Worksync listening on 0.0.0.0:${server.address.getPort} (Ctrl-C to stop)") *> IO.never
            }
        } yield ()
      }
    } yield ()
  }
}
