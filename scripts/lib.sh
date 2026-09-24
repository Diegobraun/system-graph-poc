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
