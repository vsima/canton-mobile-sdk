#!/usr/bin/env bash
# Copyright (c) 2026 Victor Sima
# SPDX-License-Identifier: Apache-2.0
#
# Regenerates the TLS fixtures in testdata/tls/ used by both SDKs' trust
# tests. Checked in rather than generated per-run so the tests need no
# certificate toolchain; dated 100 years out so they never expire out from
# under CI. Nothing here is secret — these keys only ever serve a test
# server bound to localhost.
#
# Usage: tools/gen-test-certs.sh
set -euo pipefail

out="$(cd "$(dirname "$0")/.." && pwd)/testdata/tls"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

days=36500
# Apple rejects TLS server certificates valid for more than 398 days
# (the rule that arrived with iOS 13 / macOS 10.15), so the leaf cannot be
# long-dated the way the CAs are. Regenerate before it lapses; the Swift
# trust test fails loudly when it has.
leaf_days=397

# A CA the tests pin, and a second one they pin to prove a wrong pin is
# rejected. Trust-root pinning is the shipped feature, so the fixture
# mirrors it: pin the CA, let the server present a leaf it signed.
make_ca() {
  local name="$1" cn="$2"
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$tmp/$name.key" -out "$out/$name.crt" \
    -days "$days" -subj "/CN=$cn" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
  openssl x509 -in "$out/$name.crt" -outform DER -out "$out/$name.der"
}

make_ca ca "Canton SDK Test CA"
make_ca other-ca "Canton SDK Other Test CA"

# Server leaf, signed by ca, valid for the loopback names a test server binds.
openssl req -newkey rsa:2048 -nodes \
  -keyout "$tmp/server.key.pkcs1" -out "$tmp/server.csr" \
  -subj "/CN=localhost" 2>/dev/null
cat > "$tmp/server.ext" <<EOF
subjectAltName = DNS:localhost, IP:127.0.0.1
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
EOF
openssl x509 -req -in "$tmp/server.csr" \
  -CA "$out/ca.crt" -CAkey "$tmp/ca.key" -CAcreateserial -CAserial "$tmp/ca.srl" \
  -out "$out/server.crt" -days "$leaf_days" -extfile "$tmp/server.ext" 2>/dev/null

# PKCS#8: what grpc-java's keyManager and NIOSSL both accept.
openssl pkcs8 -topk8 -nocrypt -in "$tmp/server.key.pkcs1" -out "$out/server.key"

echo "wrote:"
ls -1 "$out"
