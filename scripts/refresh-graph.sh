#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

EXTRACTOR="$ROOT/extractor/target/graph-extractor.jar"

if [ ! -f "$EXTRACTOR" ]; then
  mvn -q -B -f "$ROOT/extractor/pom.xml" package -DskipTests
fi

status=0
while IFS='|' read -r area _ _; do
  echo "== área $area"
  java -jar "$EXTRACTOR" crawl --config "$ROOT/areas/$area.yml" "$@" || status=$?
  echo
done < <(areas)
exit $status
