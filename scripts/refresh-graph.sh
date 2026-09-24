#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

EXTRACTOR="$ROOT/extractor/target/graph-extractor.jar"

if [ ! -f "$EXTRACTOR" ]; then
  mvn -q -B -f "$ROOT/extractor/pom.xml" package -DskipTests
fi

java -jar "$EXTRACTOR" crawl --config "$ROOT/crawl.yml" "$@"
