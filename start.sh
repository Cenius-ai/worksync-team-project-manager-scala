#!/usr/bin/env bash
# Worksync server start (foreground). Binds 0.0.0.0 on $PORT (default 8080).
# Migrations + idempotent demo seed run automatically during boot.
set -eu
cd "$(dirname "$0")"

PORT="${PORT:-8080}"
export PORT

echo "Starting Worksync on 0.0.0.0:${PORT}"
exec sbt -batch -Dsbt.log.noformat=true run
