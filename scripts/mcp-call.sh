#!/usr/bin/env bash
set -euo pipefail

URL="${MCP_URL:-http://localhost:8090/mcp}"
TOOL="${1:?usage: mcp-call.sh <tool> [json-arguments]}"
ARGS="${2:-}"
[ -z "$ARGS" ] && ARGS="{}"
HEADERS=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

SESSION=$(curl -s -D - -o /dev/null "${HEADERS[@]}" "$URL" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-call","version":"1"}}}' \
  | awk 'tolower($1)=="mcp-session-id:" {print $2}' | tr -d '\r')

curl -s -o /dev/null "${HEADERS[@]}" -H "Mcp-Session-Id: $SESSION" "$URL" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -s "${HEADERS[@]}" -H "Mcp-Session-Id: $SESSION" "$URL" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"$TOOL\",\"arguments\":$ARGS}}" \
  | sed -n 's/^data://p' \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); c=r.get("result",{}).get("content",[{}])[0].get("text") or json.dumps(r); print(json.dumps(json.loads(c), indent=2) if c.strip().startswith(("{","[")) else c)'
