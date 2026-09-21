#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
cd "$ROOT"
exec mvn -s scripts/maven-settings.xml -Dmaven.repo.local="$ROOT/.local/m2" -f our-table-backend/pom.xml "$@"
