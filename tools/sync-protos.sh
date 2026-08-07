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
echo "Synced Ledger API protos from canton ${VERSION}."
echo "Next: make generate && git diff"
