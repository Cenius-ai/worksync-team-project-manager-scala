# Install guide (source of truth)

Prerequisites: **JDK 21** and **sbt 1.12.x** on PATH (a network connection is needed once so sbt can resolve dependencies from Maven Central).

No other services are required — the default datastore is an embedded H2 database file created inside the project (`./data/worksync.mv.db`). Docker/PostgreSQL are **not** needed to run or demonstrate the app (an optional PostgreSQL URL override is documented below).

## Steps

1. **Install dependencies + compile + migrate + seed**

   ```bash
   sh install.sh
   ```

   This runs, in order and non-interactively, then exits:
   - `sbt compile` (first run downloads the pinned dependencies)
   - the migration runner (`pm.Seed`) — applies `V1__schema.sql` and inserts the demo dataset when the database is empty

2. **Seed step** (already run by install.sh; safe to re-run on its own — it is idempotent)

   ```bash
   sbt -batch -Dsbt.log.noformat=true "runMain pm.Seed"
   ```

3. **Run in dev / serve**

   ```bash
   bash start.sh
   # -> Worksync listening on 0.0.0.0:8080
   ```

   `start.sh` never exits on its own — stop it with Ctrl-C. It also self-heals: if the database file is missing it is created, migrated and seeded on the spot, so a bare `sbt run` works even if steps 1–2 were skipped.

## Optional configuration

Copy `.env.example` to `.env` only if you need overrides. Every value has a working default.

| Variable | Default | Notes |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port, bound on `0.0.0.0` |
| `HOST` | `0.0.0.0` | Bind address |
| `DATABASE_URL` | `jdbc:h2:./data/worksync;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` | Empty in `.env` ⇒ embedded H2. For PostgreSQL set a full URL, e.g. `jdbc:postgresql://localhost:5432/worksync`, with `DB_USER`/`DB_PASSWORD` |
| `SESSION_SECRET` | dev-only built-in fallback | Set a real value for production: `openssl rand -hex 32` |
| `COOKIE_SECURE` | `false` | Set `true` behind HTTPS |

## Verify

- `GET /health` returns `200 ok`.
- Log in with `cenius@cenius.ai` / `cenius` (Admin) or `member@worksync.dev` / `member123` (Member).
- `Persistence ready: users=8 teams=4 projects=6 tasks=20 comments=14 time_entries=17` is printed during boot.
