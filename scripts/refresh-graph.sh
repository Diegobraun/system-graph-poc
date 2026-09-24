#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

EXTRACTOR="$ROOT/extractor/target/graph-extractor.jar"

if [ ! -f "$EXTRACTOR" ]; then
  mvn -q -B -f "$ROOT/extractor/pom.xml" package -DskipTests
fi

GRAPHS=()
while IFS='|' read -r name path _; do
  dir=$(service_dir "$path")
  if [ ! -d "$dir" ]; then
    echo "skipping $name: $dir not found (run scripts/clone-services.sh)"
    continue
  fi
  mvn -q -B -f "$dir/pom.xml" compile
  java -jar "$EXTRACTOR" extract --project "$dir"
  GRAPHS+=("$dir/target/service-graph.json")
done < <(services)

java -jar "$EXTRACTOR" ingest "${GRAPHS[@]}"

if nc -z localhost 9092 2>/dev/null; then
  java -jar "$EXTRACTOR" kafka-runtime --bootstrap localhost:9092
else
  echo "kafka not reachable, skipping runtime check"
fi
