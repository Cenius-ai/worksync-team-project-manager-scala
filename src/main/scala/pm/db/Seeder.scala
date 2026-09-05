package pm.db

import cats.effect.IO
import cats.syntax.all.*
import doobie.implicits._
import doobie.util.transactor.Transactor
import pm.auth.PasswordHasher
import pm.Util
import pm.domain.{Project, Task}

import java.time.LocalDate

/** Idempotent demo seeder. Inserts demo users (bcrypt-hashed through the app's
  * own auth path), teams, projects, tasks, comments and time entries so every
  * screen renders real content immediately. Runs on the normal boot path when
  * the database has no users, and also as its own `pm.Seed` command.
  */
final class Seeder(xa: Transactor[IO]) {
  private val now = System.currentTimeMillis()
  private val today = LocalDate.now()
  private def daysAgo(n: Int): Long = now - n.toLong * 86400000L
  private def dateOffset(days: Int): String = today.plusDays(days.toLong).toString
  private def epochAt(daysAgoN: Int, hour: Int): Long =
    daysAgo(daysAgoN) - (24 - hour).toLong * 3600000L

  private def tx[A](io: doobie.ConnectionIO[A]): IO[A] = io.transact(xa)

  private def insertUser(email: String, displayName: String, plainPassword: String, role: String, active: Boolean, createdAgoDays: Int): IO[Long] =
    tx {
      sql"""INSERT INTO users (email, password_hash, display_name, role, active, created_at)
           VALUES ($email, ${PasswordHasher.hash(plainPassword)}, $displayName, $role, $active, ${daysAgo(createdAgoDays)})"""
        .update.withUniqueGeneratedKeys[Long]("id")
    }

  private def insertTeam(name: String, description: String, ownerId: Long, archived: Boolean, createdAgoDays: Int): IO[Long] =
    tx {
      sql"""INSERT INTO teams (name, description, owner_id, archived, created_at)
           VALUES ($name, $description, $ownerId, $archived, ${daysAgo(createdAgoDays)})"""
        .update.withUniqueGeneratedKeys[Long]("id")
    }

  private def insertMember(teamId: Long, userId: Long, joinedAgoDays: Int): IO[Unit] =
    tx {
      sql"""INSERT INTO team_members (team_id, user_id, joined_at)
           VALUES ($teamId, $userId, ${daysAgo(joinedAgoDays)})
          """.update.run.void
    }

  private def insertProject(teamId: Long, name: String, description: String, ownerId: Long, status: String, createdAgoDays: Int): IO[Long] =
    tx {
      sql"""INSERT INTO projects (team_id, name, description, owner_id, status, created_at)
           VALUES ($teamId, $name, $description, $ownerId, $status, ${daysAgo(createdAgoDays)})"""
        .update.withUniqueGeneratedKeys[Long]("id")
    }

  private def insertTask(
      projectId: Long,
      title: String,
      description: String,
      status: String,
      priority: String,
      assigneeId: Option[Long],
      reporterId: Long,
      dueOffsetDays: Option[Int],
      createdAgoDays: Int
  ): IO[Long] =
    tx {
      val created = daysAgo(createdAgoDays)
      sql"""INSERT INTO tasks (project_id, title, description, status, priority, assignee_id, reporter_id, due_date, created_at, updated_at)
           VALUES ($projectId, $title, $description, $status, $priority, $assigneeId, $reporterId, ${dueOffsetDays.map(dateOffset)}, $created, $created)"""
        .update.withUniqueGeneratedKeys[Long]("id")
    }

  private def insertComment(taskId: Long, authorId: Long, body: String, createdAgoDays: Int, hour: Int = 10): IO[Unit] =
    tx {
      sql"""INSERT INTO comments (task_id, author_id, body, created_at)
           VALUES ($taskId, $authorId, $body, ${epochAt(createdAgoDays, hour)})""".update.run.void
    }

  private def insertTime(taskId: Long, userId: Long, minutes: Int, note: String, entryOffsetDays: Int): IO[Unit] =
    tx {
      val date = today.plusDays(entryOffsetDays.toLong).toString
      val created = now - math.abs(entryOffsetDays.toLong) * 86400000L - 3600000L
      sql"""INSERT INTO time_entries (task_id, user_id, minutes, note, entry_date, created_at)
           VALUES ($taskId, $userId, $minutes, $note, $date, $created)""".update.run.void
    }

  def needsSeed: IO[Boolean] =
    tx(sql"SELECT COUNT(*) FROM users".query[Long].unique).map(_ == 0L)

  /** Returns unit; only seeds when the database is empty (idempotent). */
  def seedIfEmpty(): IO[Unit] =
    needsSeed.flatMap { empty =>
      if (empty) seed().flatTap(_ => IO(println("[seed] demo data inserted")))
      else IO(println("[seed] database already populated — skipping demo seed"))
    }

  private def seed(): IO[Unit] = {
    // ---------- users ----------
    for {
      _ <- IO(println("[seed] inserting demo users, teams, projects, tasks…"))
      cenius   <- insertUser("cenius@cenius.ai", "cenius", "cenius", "Admin", true, 210)
      priya    <- insertUser("admin@worksync.dev", "Priya Sharma", "admin123", "Admin", true, 190)
      marcus   <- insertUser("member@worksync.dev", "Marcus Webb", "member123", "Member", true, 160)
      elena    <- insertUser("elena@northstarlabs.io", "Elena Rodriguez", "sunrise9!", "Member", true, 150)
      tomas    <- insertUser("tomas@northstarlabs.io", "Tomas Oliveira", "quokka42!", "Member", true, 120)
      yuki     <- insertUser("yuki@blueocean.io", "Yuki Tanaka", "sakura42!", "Member", true, 140)
      sofia    <- insertUser("sofia@blueocean.io", "Sofia Marino", "marina77!", "Member", true, 100)
      jordan   <- insertUser("jordan@atlasops.io", "Jordan Lee", "coast123!", "Member", false, 300)

      // ---------- teams ----------
      northstar <- insertTeam("Northstar Labs", "Product engineering for the Northstar analytics suite — web, mobile SDK and shared platform.", priya, false, 150)
      blueOcean <- insertTeam("Blue Ocean Platform", "API gateway, customer portal and platform infrastructure for Blue Ocean.", yuki, false, 130)
      atlas     <- insertTeam("Atlas Operations", "Internal tooling for Atlas: alerting, on-call and incident response.", marcus, false, 90)
      comet     <- insertTeam("Comet Legacy App", "Maintenance squad for the retired Comet application.", priya, true, 340)

      _ <- insertMember(northstar, priya, 150); _ <- insertMember(northstar, marcus, 140)
      _ <- insertMember(northstar, elena, 120); _ <- insertMember(northstar, tomas, 110)
      _ <- insertMember(northstar, cenius, 90)
      _ <- insertMember(blueOcean, yuki, 130); _ <- insertMember(blueOcean, sofia, 100)
      _ <- insertMember(blueOcean, marcus, 80); _ <- insertMember(blueOcean, cenius, 60)
      _ <- insertMember(atlas, marcus, 90); _ <- insertMember(atlas, elena, 40)
      _ <- insertMember(comet, priya, 340); _ <- insertMember(comet, jordan, 300)

      // ---------- projects ----------
      pNorthstarWeb <- insertProject(northstar, "Northstar Web App", "Customer-facing web app: dashboard, reports and account management for the Northstar analytics suite.", priya, Project.StatusActive, 120)
      pMobileSdk    <- insertProject(northstar, "Mobile Analytics SDK", "iOS/Android SDK shipping real-time analytics events with offline batching.", elena, Project.StatusActive, 80)
      pGateway      <- insertProject(blueOcean, "Blue Ocean API Gateway", "Rate limiting, authentication and webhooks for all Blue Ocean public APIs.", yuki, Project.StatusActive, 110)
      pPortal       <- insertProject(blueOcean, "Customer Portal Refresh", "Redesign of the customer portal information architecture and self-service flows.", sofia, Project.StatusArchived, 160)
      pAtlas        <- insertProject(atlas, "Atlas Ops Console", "Alert routing, on-call schedules and incident timeline tooling for Atlas Operations.", marcus, Project.StatusActive, 70)
      pComet        <- insertProject(comet, "Comet Legacy Maintenance", "Dependency refresh and security patching for the Comet legacy application.", priya, Project.StatusArchived, 320)

      // ---------- tasks (Northstar Web App) ----------
      t1 <- insertTask(pNorthstarWeb, "SSO login flow for partner accounts", "Partners should sign in through their own identity provider. Map the SAML/OIDC claims to internal team memberships and keep the existing email+password fallback.", Task.StatusInProgress, Task.PriorityHigh, Some(marcus), priya, Some(2), 20)
      t2 <- insertTask(pNorthstarWeb, "Audit dashboard query performance", "Profile the top 10 slowest dashboard queries on the production replica and index the hot paths. Target < 100ms p95 for report loads.", Task.StatusTodo, Task.PriorityMedium, Some(elena), priya, Some(6), 6)
      t3 <- insertTask(pNorthstarWeb, "Empty states for the reports module", "Every report screen needs a designed empty state with a clear next action before we ship the module.", Task.StatusDone, Task.PriorityLow, Some(tomas), priya, Some(-4), 24)
      t4 <- insertTask(pNorthstarWeb, "Fix timezone drift in scheduled exports", "Scheduled CSV exports run at the wrong local time for APAC workspaces — dates shift by a day when daylight saving changes.", Task.StatusTodo, Task.PriorityHigh, Some(cenius), priya, Some(1), 2)
      t5 <- insertTask(pNorthstarWeb, "Accessibility pass on the app shell", "Keyboard-navigate the top bar and global filter bar, add focus-visible rings and aria labels; fix the two contrast failures from the last audit.", Task.StatusInProgress, Task.PriorityMedium, Some(elena), priya, Some(9), 8)
      t6 <- insertTask(pNorthstarWeb, "Retry logic for billing webhooks", "Idempotent retry with exponential backoff for Stripe webhook delivery; dead-letter events after 8 attempts.", Task.StatusDone, Task.PriorityMedium, Some(marcus), priya, Some(-2), 30)

      // ---------- tasks (Mobile SDK) ----------
      t7 <- insertTask(pMobileSdk, "Cold-start trace instrumentation", "Capture launch traces before the SDK finishes initialising so cold-start regressions show up in the dashboard.", Task.StatusInProgress, Task.PriorityHigh, Some(tomas), elena, Some(-1), 12)
      t8 <- insertTask(pMobileSdk, "Event batching before flush", "Coalesce session events into larger batched payloads to cut request volume on flaky mobile networks.", Task.StatusTodo, Task.PriorityMedium, Some(tomas), elena, Some(4), 15)
      t9 <- insertTask(pMobileSdk, "Write release notes for v0.9", "Changelog covering the new offline queue and the privacy toggle; needs sign-off from product.", Task.StatusDone, Task.PriorityLow, Some(elena), elena, Some(-3), 9)
      t10 <- insertTask(pMobileSdk, "Crash repro for iOS 18 background sessions", "App crashes when a background upload task completes after the session is invalidated. Need a reproducible sample project.", Task.StatusTodo, Task.PriorityHigh, Some(cenius), elena, Some(0), 1)

      // ---------- tasks (API Gateway) ----------
      t11 <- insertTask(pGateway, "Rate-limit tier enforcement", "Enforce per-key rate limits at the gateway edge: burst + sustained windows with 429s and Retry-After headers.", Task.StatusInProgress, Task.PriorityHigh, Some(sofia), yuki, Some(3), 14)
      t12 <- insertTask(pGateway, "Observability dashboard for the gateway", "Aggregate latency, error rate and throttle events per route into a live dashboard for the platform team.", Task.StatusTodo, Task.PriorityMedium, Some(yuki), yuki, Some(10), 5)
      t13 <- insertTask(pGateway, "JWT audience validation", "Reject tokens whose audience does not match the requested service; add aud to the introspection cache key.", Task.StatusDone, Task.PriorityHigh, Some(marcus), yuki, Some(-5), 18)
      t14 <- insertTask(pGateway, "Webhook signature rotation", "Support rotated signing keys with a grace period so consumers are not broken when the active key changes.", Task.StatusTodo, Task.PriorityMedium, Some(sofia), yuki, Some(7), 3)

      // ---------- tasks (archived Customer Portal) ----------
      t15 <- insertTask(pPortal, "Consolidate customer profile views", "Merge usage, billing and contact data into one profile screen; archive the old fragmented views.", Task.StatusInProgress, Task.PriorityMedium, Some(sofia), sofia, Some(-10), 40)
      t16 <- insertTask(pPortal, "Migrate portal to the new CMS", "Move marketing-style pages to the headless CMS and remove the legacy page builder.", Task.StatusDone, Task.PriorityMedium, Some(yuki), sofia, Some(-20), 60)

      // ---------- tasks (Atlas Ops) ----------
      t17 <- insertTask(pAtlas, "Alert routing rules editor", "Let on-call engineers author routing rules (service → team → channel) without touching config files.", Task.StatusTodo, Task.PriorityHigh, Some(marcus), marcus, Some(5), 11)
      t18 <- insertTask(pAtlas, "On-call schedule sync", "Two-way sync with Google Calendar so the on-call calendar always matches the pager rotations.", Task.StatusInProgress, Task.PriorityMedium, Some(elena), marcus, Some(1), 16)
      t19 <- insertTask(pAtlas, "Incident timeline view", "Render a scrollable timeline of events (paged, acknowledged, resolved) per incident with annotations.", Task.StatusDone, Task.PriorityLow, Some(marcus), marcus, Some(-6), 25)

      // ---------- tasks (Comet legacy) ----------
      _ <- insertTask(pComet, "Quarterly dependency refresh", "Bump the EOL runtime and libraries; verify the smoke suite passes before the freeze window.", Task.StatusDone, Task.PriorityMedium, Some(jordan), priya, Some(-60), 300)

      // ---------- comments ----------
      _ <- insertComment(t1, priya, "Partner onboarding doc lists three IdPs we need to support in the first cut.", 18, 9)
      _ <- insertComment(t1, marcus, "I wired up the OIDC discovery flow; SAML metadata import is next on my list.", 12, 15)
      _ <- insertComment(t1, elena, "Design mocks for the 'choose your provider' screen are in the shared drive.", 8, 11)
      _ <- insertComment(t4, priya, "Repro case: exports scheduled at 02:00 UTC land one day early for Sydney workspaces.", 1, 14)
      _ <- insertComment(t5, marcus, "Ran axe on the current shell — 3 contrast violations left to fix.", 6, 13)
      _ <- insertComment(t7, tomas, "Crash symbolication now includes the launch phase, so we can see the full cold-start trace.", 5, 16)
      _ <- insertComment(t10, elena, "Filed the sample project under ios-crashes/background-upload if anyone wants to reproduce.", 1, 9)
      _ <- insertComment(t11, yuki, "Let's expose the burst window as a config value per tier so sales can trial larger bursts.", 9, 10)
      _ <- insertComment(t11, sofia, "429 response shape matches the API reference; Retry-After is in seconds.", 4, 12)
      _ <- insertComment(t13, marcus, "Audience validation is live on staging — watch the rejection counters this week.", 16, 17)
      _ <- insertComment(t17, marcus, "First pass of the routing DSL is on the branch; example rules in the README.", 7, 15)
      _ <- insertComment(t18, elena, "ICS feed parsing done; the calendar side now needs the sync loop.", 13, 10)
      _ <- insertComment(t19, marcus, "Annotations render inline on the timeline now; next is the CSV export.", 22, 11)
      _ <- insertComment(t2, priya, "The new report endpoint is the worst offender — 40% of the query time is in the joins.", 4, 8)

      // ---------- time entries (recent days so the 7-day dashboard card shows life) ----------
      _ <- insertTime(t1, marcus, 120, "OIDC discovery + claim mapping", -1)
      _ <- insertTime(t1, marcus, 90, "SAML metadata import spike", -4)
      _ <- insertTime(t4, cenius, 75, "Timezone repro with Sydney workspace", -1)
      _ <- insertTime(t4, cenius, 45, "Auditing export scheduler code", -2)
      _ <- insertTime(t5, elena, 60, "Keyboard nav audit of the shell", -3)
      _ <- insertTime(t7, tomas, 150, "Launch phase instrumentation", -2)
      _ <- insertTime(t7, tomas, 90, "Trace symbolication hookup", -5)
      _ <- insertTime(t10, cenius, 60, "Digging through iOS crash logs", 0)
      _ <- insertTime(t11, sofia, 180, "Burst window config + 429 headers", -3)
      _ <- insertTime(t11, yuki, 45, "Tier spec review", -6)
      _ <- insertTime(t13, marcus, 90, "Audience cache key change", -12)
      _ <- insertTime(t17, marcus, 120, "Routing DSL first pass", -2)
      _ <- insertTime(t17, marcus, 60, "Example rules + tests", -7)
      _ <- insertTime(t18, elena, 110, "ICS parsing + sync loop", -4)
      _ <- insertTime(t19, marcus, 45, "Timeline annotation rendering", -16)
      _ <- insertTime(t3, tomas, 30, "Empty state copy review", -20)
      _ <- insertTime(t9, elena, 25, "Changelog drafts", -18)
    } yield ()
  }

  def logCounts(): IO[Unit] = {
    def count(table: String): IO[Long] =
      tx(doobie.Fragment.const(s"SELECT COUNT(*) FROM $table").query[Long].unique)
    for {
      u <- count("users"); te <- count("teams"); p <- count("projects")
      t <- count("tasks"); c <- count("comments"); ti <- count("time_entries")
      _ <- IO(println(s"Persistence ready: users=$u teams=$te projects=$p tasks=$t comments=$c time_entries=$ti"))
    } yield ()
  }
}
