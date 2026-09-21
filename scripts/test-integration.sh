#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/.local"
TEMP_DIR=$(mktemp -d "$ROOT/.local/integration.XXXXXX")
MYSQL_ID=''
REDIS_ID=''
cleanup() {
  [[ -z "$MYSQL_ID" ]] || docker stop "$MYSQL_ID" >/dev/null
  [[ -z "$REDIS_ID" ]] || docker stop "$REDIS_ID" >/dev/null
}
trap cleanup EXIT
python3 - "$TEMP_DIR" <<'PY'
from pathlib import Path
import sys,secrets,os
p=Path(sys.argv[1]);mysql=secrets.token_urlsafe(24);redis=secrets.token_urlsafe(24)
files={
 'mysql.env':f'MYSQL_DATABASE=myhome_it\nMYSQL_USER=myhome_it\nMYSQL_PASSWORD={mysql}\nMYSQL_ROOT_PASSWORD={secrets.token_urlsafe(24)}\n',
 'redis.conf':f'bind 0.0.0.0\nrequirepass {redis}\nappendonly no\n',
 'application.properties':f'spring.datasource.url=jdbc:mysql://127.0.0.1:13306/myhome_it?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8\nspring.datasource.username=myhome_it\nspring.datasource.password={mysql}\nspring.data.redis.host=127.0.0.1\nspring.data.redis.port=16379\nspring.data.redis.password={redis}\napp.security.encryption-key={secrets.token_urlsafe(32)}\nspring.flyway.enabled=true\n'
}
for name,text in files.items():
 f=p/name;f.write_text(text);os.chmod(f,0o600)
PY
MYSQL_ID=$(docker run --rm -d --env-file "$TEMP_DIR/mysql.env" -p 127.0.0.1:13306:3306 mysql:8)
REDIS_ID=$(docker run --rm -d -p 127.0.0.1:16379:6379 -v "$TEMP_DIR/redis.conf:/usr/local/etc/redis/redis.conf:ro" redis:7-alpine redis-server /usr/local/etc/redis/redis.conf)
READY=false
for attempt in {1..60}; do
  if docker exec "$MYSQL_ID" sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h127.0.0.1 -u"$MYSQL_USER" -e "SELECT 1"' >/dev/null 2>&1; then READY=true; break; fi
  sleep 1
done
[[ "$READY" == true ]] || { echo '测试 MySQL 未在60秒内就绪'; exit 1; }
RUN_LOCAL_INTEGRATION=true OUR_TABLE_TEST_CONFIG="$TEMP_DIR/application.properties" bash "$ROOT/scripts/backend.sh" -B -ntp verify
