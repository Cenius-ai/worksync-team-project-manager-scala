#!/usr/bin/env bash
# Worksync install: deps (none beyond sbt), compile, migrate + seed. EXITS.
set -eu
cd "$(dirname "$0")"

echo "[install] Worksync — compiling with sbt (first run downloads dependencies)"
sbt -batch -Dsbt.log.noformat=true compile

echo "[install] applying migrations and seeding demo data"
sbt -batch -Dsbt.log.noformat=true "runMain pm.Seed"

echo ""
echo "Install complete."
echo "  Next:  bash start.sh"
echo "  Demo:  cenius@cenius.ai / cenius  (full admin)"
echo "         admin@worksync.dev / admin123   ·   member@worksync.dev / member123"
