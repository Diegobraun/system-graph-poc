#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

RUN="$ROOT/.run"
mkdir -p "$RUN"

docker compose -f "$ROOT/docker-compose.yml" up -d --wait

start() {
  local name=$1 dir=$2
  local jar
  jar=$(ls "$dir"/target/*-SNAPSHOT.jar 2>/dev/null | head -1 || true)
  if [ -z "$jar" ]; then
    mvn -q -B -f "$dir/pom.xml" package -DskipTests
    jar=$(ls "$dir"/target/*-SNAPSHOT.jar | head -1)
  fi
  nohup java -jar "$jar" > "$RUN/$name.log" 2>&1 &
  echo $! > "$RUN/$name.pid"
  echo "started $name (pid $!, log .run/$name.log)"
}

while IFS='|' read -r name path _; do
  dir=$(service_dir "$path")
  if [ -d "$dir" ]; then
    start "$name" "$dir"
  else
    echo "skipping $name: $dir not found (run scripts/clone-services.sh)"
  fi
done < <(services)
start graph-mcp-server "$ROOT/graph-mcp-server"

for port in 8081 8082 8090; do
  for _ in $(seq 1 60); do
    nc -z localhost "$port" 2>/dev/null && break
    sleep 1
  done
done
echo "account-service :8081  loan-service :8082  graph-mcp-server :8090/mcp"
