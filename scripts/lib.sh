ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

services() {
  grep -v '^\s*$' "$ROOT/scripts/services.conf"
}

service_dir() {
  local path=$1
  case "$path" in
    /*) echo "$path" ;;
    *) echo "$ROOT/$path" ;;
  esac
}

HUB_URI=${HUB_URI:-bolt://localhost:7690}

areas() {
  grep -v '^\s*$' "$ROOT/scripts/areas.conf"
}

links() {
  printf 'empresa=http://localhost:8090/'
  while IFS='|' read -r area _ port; do printf ',%s=http://localhost:%s/' "$area" "$port"; done < <(areas)
}
