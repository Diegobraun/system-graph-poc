#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

EXTRACTOR="$ROOT/extractor/target/graph-extractor.jar"
[ -f "$EXTRACTOR" ] || mvn -q -B -f "$ROOT/extractor/pom.xml" package -DskipTests

java -jar "$EXTRACTOR" experimental import-openapi --service core-banking --spec "$ROOT/examples/core-banking.openapi.yml" "$@"
java -jar "$EXTRACTOR" experimental import-observed-calls --file "$ROOT/examples/observed-calls.json" \
  --names "$ROOT/examples/apm-names.yml" --source apm-exemplo "$@"
