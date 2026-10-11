#!/usr/bin/env bash
# Builds the server and (re)deploys it to the VPS over SSH key auth. Safe to re-run: the
# database in /opt/lumiscan/data is kept, the container is rebuilt, nginx is only reloaded after
# its config test passes.
#
#   server/deploy/deploy.sh                 # root@134.185.119.124
#   server/deploy/deploy.sh root@1.2.3.4
set -euo pipefail

HOST="${1:-root@134.185.119.124}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
REMOTE=/opt/lumiscan
NGINX_CONF=/www/server/panel/vhost/nginx/lumiscan.conf

# The build needs JDK 17: without JAVA_HOME, use the one that comes with the Android build setup.
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/android-build/jdk/Contents/Home" ]; then
  export JAVA_HOME="$HOME/android-build/jdk/Contents/Home"
fi

if [ ! -f "$HERE/tls/server.key" ]; then
  echo "Missing $HERE/tls/server.key — run server/deploy/gen-cert.sh <ip> first." >&2
  exit 1
fi

echo "==> Building server jar"
(cd "$ROOT" && ./gradlew -p server fatJar -q --console=plain)

echo "==> Uploading to $HOST:$REMOTE"
ssh "$HOST" "mkdir -p $REMOTE/tls $REMOTE/data && chmod 700 $REMOTE/tls"
scp -q "$ROOT/server/build/libs/lumiscan-server.jar" "$HERE/Dockerfile" "$HERE/docker-compose.yml" "$HOST:$REMOTE/"
scp -q "$HERE/tls/server.crt" "$HERE/tls/server.key" "$HOST:$REMOTE/tls/"
scp -q "$HERE/nginx-lumiscan.conf" "$HOST:$REMOTE/nginx-lumiscan.conf"

echo "==> Starting container and reloading nginx"
ssh "$HOST" REMOTE="$REMOTE" NGINX_CONF="$NGINX_CONF" bash -s <<'EOF'
set -euo pipefail
cd "$REMOTE"
chmod 600 tls/server.key
chown -R 10001:10001 data
docker compose up -d --build --remove-orphans
docker image prune -f >/dev/null

for i in $(seq 1 60); do
  if curl -fsS -m 2 http://127.0.0.1:8090/healthz >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS -m 2 http://127.0.0.1:8090/healthz >/dev/null || { echo "server did not become healthy"; docker logs --tail 50 lumiscan; exit 1; }

cp nginx-lumiscan.conf "$NGINX_CONF"
if ! nginx -t 2>/tmp/lumiscan-nginx-test.log; then
  cat /tmp/lumiscan-nginx-test.log
  rm -f "$NGINX_CONF"
  echo "nginx config test failed; removed $NGINX_CONF so the other sites keep working"
  exit 1
fi
nginx -s reload
sleep 1
curl -fsS -m 5 --cacert tls/server.crt https://134.185.119.124/healthz >/dev/null && echo "HTTPS OK: https://134.185.119.124/admin/"

if [ -f data/initial-admin.txt ]; then
  echo
  cat data/initial-admin.txt
fi
EOF
