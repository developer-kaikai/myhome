#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
JDBC=$(find "$ROOT/.local/m2/com/mysql/mysql-connector-j" -name 'mysql-connector-j-*.jar' -type f | sort | tail -1)
[[ -n "$JDBC" ]] || { echo '请先执行 bash scripts/backend.sh package'; exit 1; }
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$JDBC" "$ROOT/scripts/DbAdmin.java" "$ROOT/.local/application-local.properties" "$@"
