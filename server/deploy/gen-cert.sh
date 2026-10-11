#!/usr/bin/env bash
# Generates the self-signed TLS certificate for an IP-only server (no domain yet) and copies the
# public certificate into the Android app, which pins it. Run once; re-running replaces the pair,
# after which the app must be rebuilt.
#
#   server/deploy/gen-cert.sh 134.185.119.124
set -euo pipefail

IP="${1:?usage: gen-cert.sh <server-ip>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/tls"
APP_RAW="$HERE/../../app/src/main/res/raw"
mkdir -p "$OUT" "$APP_RAW"

CONF="$(mktemp)"
trap 'rm -f "$CONF"' EXIT
cat > "$CONF" <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $IP
O = LumiScan
[ext]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = IP:$IP
subjectKeyIdentifier = hash
EOF

openssl ecparam -name prime256v1 -genkey -noout -out "$OUT/server.key"
openssl req -new -x509 -key "$OUT/server.key" -out "$OUT/server.crt" -days 3650 -sha256 -config "$CONF"
chmod 600 "$OUT/server.key"
cp "$OUT/server.crt" "$APP_RAW/server_cert.pem"

openssl x509 -in "$OUT/server.crt" -noout -subject -enddate
openssl x509 -in "$OUT/server.crt" -noout -text | grep -A1 "Subject Alternative Name"
echo "Certificate copied to app/src/main/res/raw/server_cert.pem (private key stays in server/deploy/tls/)."
