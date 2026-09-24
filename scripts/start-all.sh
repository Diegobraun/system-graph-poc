#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/.run"
mkdir -p "$RUN"

docker compose -f "$ROOT/docker-compose.yml" up -d --wait

for module in account-service loan-service graph-mcp-server; do
  jar=$(ls "$ROOT/$module"/target/*-SNAPSHOT.jar 2>/dev/null | head -1 || true)
  if [ -z "$jar" ]; then
    mvn -q -B -f "$ROOT/$module/pom.xml" package -DskipTests
    jar=$(ls "$ROOT/$module"/target/*-SNAPSHOT.jar | head -1)
  fi
  nohup java -jar "$jar" > "$RUN/$module.log" 2>&1 &
  echo $! > "$RUN/$module.pid"
  echo "started $module (pid $!, log .run/$module.log)"
done

for port in 8081 8082 8090; do
  for _ in $(seq 1 60); do
    nc -z localhost "$port" 2>/dev/null && break
    sleep 1
  done
done
echo "account-service :8081  loan-service :8082  graph-mcp-server :8090/mcp"
