#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE="${1:?用法：bash scripts/run-service.sh core|ordering|party [Spring参数]}"
case "$SERVICE" in core|ordering|party) ;; *) echo '服务只能为 core、ordering、party'; exit 1;; esac
shift
if [[ -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
cd "$ROOT"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "our-table-backend/our-table-$SERVICE/target/our-table-$SERVICE-0.1.0-SNAPSHOT.jar" "$@"
