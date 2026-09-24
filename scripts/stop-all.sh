#!/usr/bin/env bash
set -uo pipefail

source "$(dirname "$0")/lib.sh"

for pidfile in "$ROOT"/.run/*.pid; do
  [ -f "$pidfile" ] || continue
  kill "$(cat "$pidfile")" 2>/dev/null && echo "stopped $(basename "$pidfile" .pid)"
  rm -f "$pidfile"
done

if [ "${1:-}" = "--all" ]; then
  docker compose -f "$ROOT/docker-compose.yml" down
fi
