#!/usr/bin/env bash
# Prints the plugins directory of the most recently used PyCharm installation.
#
# Resolution order:
#   1. the PLUGINS_DIR environment variable, when set;
#   2. the newest "PyCharm*" data directory under the usual JetBrains roots.
#
# Exits with status 1 and a short explanation when nothing is found.

set -euo pipefail

if [ -n "${PLUGINS_DIR:-}" ]; then
  printf '%s\n' "$PLUGINS_DIR"
  exit 0
fi

data_home="${XDG_DATA_HOME:-$HOME/.local/share}"

roots=(
  "$data_home/JetBrains"
  "$HOME/Library/Application Support/JetBrains"
)
if [ -n "${APPDATA:-}" ]; then
  roots+=("$APPDATA/JetBrains")
fi

best=""
best_time=0

for root in "${roots[@]}"; do
  [ -d "$root" ] || continue
  shopt -s nullglob
  for candidate in "$root"/PyCharm*; do
    [ -d "$candidate/plugins" ] || continue
    mtime=$(stat -c %Y "$candidate" 2>/dev/null || stat -f %m "$candidate" 2>/dev/null || echo 0)
    if [ "$mtime" -ge "$best_time" ]; then
      best="$candidate/plugins"
      best_time="$mtime"
    fi
  done
  shopt -u nullglob
done

if [ -z "$best" ]; then
  cat >&2 <<'EOF'
error: could not find a PyCharm plugins directory.

Pass it explicitly with PLUGINS_DIR, for example:

  make install PLUGINS_DIR="$HOME/.local/share/JetBrains/PyCharm2025.1/plugins"                 # Linux
  make install PLUGINS_DIR="$HOME/Library/Application Support/JetBrains/PyCharm2025.1/plugins" # macOS
  make install PLUGINS_DIR="$APPDATA/JetBrains/PyCharm2025.1/plugins"                          # Windows (Git Bash)

PyCharm keeps plugins next to its configuration:

  Linux    ~/.local/share/JetBrains/PyCharm<version>/plugins
  macOS    ~/Library/Application Support/JetBrains/PyCharm<version>/plugins
  Windows  %APPDATA%\JetBrains\PyCharm<version>\plugins
EOF
  exit 1
fi

printf '%s\n' "$best"
