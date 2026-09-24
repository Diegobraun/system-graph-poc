#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

while IFS='|' read -r name path url _; do
  dir=$(service_dir "$path")
  if [ -d "$dir/.git" ]; then
    git -C "$dir" pull -q --ff-only && echo "updated $name ($dir)"
  else
    git clone -q "$url" "$dir" && echo "cloned $name into $dir"
  fi
done < <(services)
