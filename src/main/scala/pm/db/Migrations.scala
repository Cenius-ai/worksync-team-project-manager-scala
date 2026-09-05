package pm.db

import cats.effect.IO
import cats.syntax.all._
import doobie.implicits._
import doobie.util.transactor.Transactor
import pm.Util

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets

/** Minimal versioned migration runner over the db/migration resource dir.
  * Files are applied in version order once each (tracked in schema_version);
  * the DDL itself is written with IF NOT EXISTS so re-runs are harmless.
  */
final class Migrations(xa: Transactor[IO]) {
  private val MigrationsDir = "db/migration"

  /** Versioned migration files, applied in listed order. */
  private val Files: List[String] = List(
    "V1__schema.sql"
  )

  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  private def readResource(name: String): String = {
    val is: InputStream = getClass.getClassLoader.getResourceAsStream(s"$MigrationsDir/$name")
    if (is == null) throw new IllegalStateException(s"Migration resource not found: $name")
    try {
      val out = new ByteArrayOutputStream()
      val buf = new Array[Byte](8192)
      var n = is.read(buf)
      while (n >= 0) {
        if (n > 0) out.write(buf, 0, n)
        n = is.read(buf)
      }
      out.toString(StandardCharsets.UTF_8)
    } finally is.close()
  }

  private def statements(sql: String): List[String] = {
    val lines = sql.linesIterator
      .map(_.trim)
      .filterNot(l => l.isEmpty || l.startsWith("--"))
      .mkString("\n")
    lines
      .split(";")
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
  }

  def run(): IO[Unit] =
    for {
      _ <- tx(sql"""CREATE TABLE IF NOT EXISTS schema_version (
                      version VARCHAR(120) PRIMARY KEY,
                      applied_at BIGINT NOT NULL
                    )""".update.run.void)
      applied <- tx(sql"SELECT version FROM schema_version".query[String].to[Set])
      _ <- Files.foldLeft(IO.unit) { (acc, name) =>
        acc.flatMap { _ =>
          if (applied.contains(name)) IO.unit
          else
            for {
              _ <- IO(println(s"[migrate] applying $name"))
              body = readResource(name)
              _ <- statements(body).foldLeft(IO.unit) { (a, stmt) =>
                a.flatMap(_ => tx(doobie.Fragment.const(stmt).update.run.void))
              }
              _ <- tx(sql"INSERT INTO schema_version (version, applied_at) VALUES ($name, ${Util.nowMillis})".update.run.void)
            } yield ()
        }
      }
    } yield ()

  /** Run migrations for command-line / seed usage and print what happened. */
  def runAndLog(): IO[Unit] =
    run().flatTap(_ => IO(println("[migrate] schema up to date")))
}
