#!/usr/bin/env bash
# End-to-end API test: starts a mock OpenAI-compatible model and a fresh server in a temp
# directory, runs e2e.py against it, then stops both.
#
#   server/test-e2e/run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TMP="$(mktemp -d)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

(cd "$ROOT" && ./gradlew -p server fatJar -q --console=plain)

python3 -I "$HERE/mock_ai.py" &
MOCK=$!
LUMI_PORT=18080 LUMI_HOST=127.0.0.1 LUMI_DATA="$TMP" LUMI_COOKIE_SECURE=false LUMI_ADMIN_PASSWORD=InitPass12345 \
  "$JAVA" -Xmx160m -jar "$ROOT/server/build/libs/lumiscan-server.jar" > "$TMP/server.log" 2>&1 &
SERVER=$!
trap 'kill $MOCK $SERVER 2>/dev/null; rm -rf "$TMP"' EXIT

for _ in $(seq 1 60); do curl -fs -m 1 http://127.0.0.1:18080/healthz >/dev/null && break; sleep 0.5; done
python3 -I "$HERE/e2e.py" http://127.0.0.1:18080
