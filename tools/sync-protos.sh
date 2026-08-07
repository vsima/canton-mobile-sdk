#!/usr/bin/env bash
set -euo pipefail

# Syncs the vendored Canton Ledger API protos from a pinned canton release.
#
# Usage:
#   tools/sync-protos.sh            # re-sync the version in proto/UPSTREAM_VERSION
#   tools/sync-protos.sh 3.5.11     # sync a specific canton version and update the pin
#
# Only the proto roots needed by the mobile SDKs are vendored. After syncing,
# regenerate stubs with `make generate` and review the diff.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-$(cat "$ROOT/proto/UPSTREAM_VERSION")}"
PROTO_ROOTS=(ledger-api ledger-api-value)

URL="https://github.com/digital-asset/canton/releases/download/v${VERSION}/canton-open-source-${VERSION}-protobuf.tar.gz"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Fetching $URL"
curl -fsSL "$URL" -o "$TMP/protobuf.tar.gz"
tar -xzf "$TMP/protobuf.tar.gz" -C "$TMP"

for proto_root in "${PROTO_ROOTS[@]}"; do
  SRC="$TMP/canton-open-source-${VERSION}/protobuf/${proto_root}"
  if [ ! -d "$SRC" ]; then
    echo "error: expected proto root '${proto_root}' not found in release bundle" >&2
    exit 1
  fi
  rm -rf "${ROOT:?}/proto/${proto_root}"
  cp -R "$SRC" "$ROOT/proto/${proto_root}"
done

echo "$VERSION" > "$ROOT/proto/UPSTREAM_VERSION"

# google.rpc protos (referenced by the Ledger API, e.g. Completion.status, and
# by Canton's rich error details), vendored from googleapis at a pinned ref and
# compiled into both SDKs. Bump proto/googleapis/GOOGLEAPIS_REF to update.
GOOGLEAPIS_REF="$(cat "$ROOT/proto/googleapis/GOOGLEAPIS_REF")"
for f in status error_details; do
  curl -fsSL "https://raw.githubusercontent.com/googleapis/googleapis/${GOOGLEAPIS_REF}/google/rpc/${f}.proto" \
    -o "$ROOT/proto/googleapis/google/rpc/${f}.proto"
done

echo "Synced Ledger API protos from canton ${VERSION} (googleapis @ ${GOOGLEAPIS_REF:0:12})."
echo "Next: make generate && git diff"
