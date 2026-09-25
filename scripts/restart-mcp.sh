#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

RUN="$ROOT/.run"
JAR=$(ls "$ROOT"/graph-mcp-server/target/graph-mcp-server-*-SNAPSHOT.jar | head -1)

launch() {
  local name=$1
  shift
  [ -f "$RUN/$name.pid" ] && kill "$(cat "$RUN/$name.pid")" 2>/dev/null || true
  env "$@" nohup java -jar "$JAR" > "$RUN/$name.log" 2>&1 &
  echo $! > "$RUN/$name.pid"
}

launch graph-mcp-hub NEO4J_URI="$HUB_URI" SERVER_PORT=8090 GRAPH_LINKS="$(links)"
PORTS=(8090)
while IFS='|' read -r area bolt port; do
  launch "graph-mcp-$area" NEO4J_URI="$bolt" GRAPH_AREA="$area" GRAPH_HUB_URI="$HUB_URI" SERVER_PORT="$port" GRAPH_LINKS="$(links)"
  PORTS+=("$port")
done < <(areas)
sleep 2
for port in "${PORTS[@]}"; do
  for _ in $(seq 1 60); do nc -z localhost "$port" 2>/dev/null && break; sleep 1; done
done
echo "graph-mcp-hub :8090 · $(awk -F'|' '{printf "graph-mcp-%s :%s · ", $1, $3}' "$ROOT/scripts/areas.conf")"
