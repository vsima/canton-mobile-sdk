#!/usr/bin/env bash
set -euo pipefail

# Downloads the pinned canton open-source release (version from
# proto/UPSTREAM_VERSION, override with CANTON_VERSION=x.y.z) and starts a
# local participant + synchronizer using the release's own simple-topology
# example. Requires a JVM (17+), curl, and tar.
#
# The Ledger API ports are defined in the example config — see
# examples/01-simple-topology/simple-topology.conf inside the extracted
# distribution.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${CANTON_VERSION:-$(cat "$ROOT/proto/UPSTREAM_VERSION")}"
CACHE="$ROOT/integration/.cache"
DIST="$CACHE/canton-open-source-${VERSION}"

if [ ! -d "$DIST" ]; then
  mkdir -p "$CACHE"
  URL="https://github.com/digital-asset/canton/releases/download/v${VERSION}/canton-open-source-${VERSION}.tar.gz"
  echo "Fetching $URL"
  curl -fL "$URL" | tar -xz -C "$CACHE"
fi

CONF="$DIST/examples/01-simple-topology/simple-topology.conf"
BOOTSTRAP="$DIST/examples/01-simple-topology/simple-ping.canton"

exec "$DIST/bin/canton" daemon -c "$CONF" --bootstrap "$BOOTSTRAP"
