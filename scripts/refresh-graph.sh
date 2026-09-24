#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXTRACTOR="$ROOT/extractor/target/graph-extractor.jar"
SERVICES=(account-service loan-service)

if [ ! -f "$EXTRACTOR" ]; then
  mvn -q -B -f "$ROOT/extractor/pom.xml" package -DskipTests
fi

GRAPHS=()
for service in "${SERVICES[@]}"; do
  mvn -q -B -f "$ROOT/$service/pom.xml" compile
  java -jar "$EXTRACTOR" extract --project "$ROOT/$service"
  GRAPHS+=("$ROOT/$service/target/service-graph.json")
done

java -jar "$EXTRACTOR" ingest "${GRAPHS[@]}"

if nc -z localhost 9092 2>/dev/null; then
  java -jar "$EXTRACTOR" kafka-runtime --bootstrap localhost:9092
else
  echo "kafka not reachable, skipping runtime check"
fi
