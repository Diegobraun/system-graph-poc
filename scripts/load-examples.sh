#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

EXTRACTOR="$ROOT/extractor/target/graph-extractor.jar"
[ -f "$EXTRACTOR" ] || mvn -q -B -f "$ROOT/extractor/pom.xml" package -DskipTests

CONTAS=$(awk -F'|' '$1 == "contas" {print $2}' "$ROOT/scripts/areas.conf")
for uri in "$CONTAS" "$HUB_URI"; do
  java -jar "$EXTRACTOR" experimental import-openapi --service core-banking --spec "$ROOT/examples/core-banking.openapi.yml" --neo4j-uri "$uri"
done

observed() {
  java -jar "$EXTRACTOR" experimental import-observed-calls --file "$ROOT/examples/observed-calls.json" \
    --names "$ROOT/examples/apm-names.yml" --source apm-exemplo "$@"
}
observed --neo4j-uri "$HUB_URI"
while IFS='|' read -r area bolt _; do
  echo "== $area"
  observed --only-indexed --neo4j-uri "$bolt"
done < <(areas)
