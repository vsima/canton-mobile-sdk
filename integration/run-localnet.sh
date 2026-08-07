#!/usr/bin/env bash
set -euo pipefail

# Downloads the pinned Splice release bundle and starts Splice LocalNet
# (SV + app-provider + app-user) via docker compose — a real Amulet
# registry for token-standard integration tests. Requires Docker.
#
# Endpoints once healthy (see env/common.env for the port scheme):
#   app-user Ledger API (gRPC):  localhost:2901   (unsafe HS256 JWT, secret
#     "unsafe", audience https://canton.network.global, admin user
#     "ledger-api-user")
#   validator (wallet) API:      http://wallet.localhost:2000/api/validator
#   scan / registry API:         http://scan.localhost:4000/api/scan
#
# Run the token-standard LocalNet test with: SPLICE_LOCALNET=1
#
# Note: mining rounds take a few minutes to open after first boot; the
# integration test retries tap until they do.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${SPLICE_VERSION:-0.7.1}"
CACHE="$ROOT/integration/.cache/splice"
BUNDLE="$CACHE/${VERSION}_splice-node.tar.gz"
LOCALNET_DIR="$CACHE/splice-node/docker-compose/localnet"

if [ ! -d "$LOCALNET_DIR" ]; then
  mkdir -p "$CACHE"
  URL="https://github.com/digital-asset/decentralized-canton-sync/releases/download/v${VERSION}/${VERSION}_splice-node.tar.gz"
  echo "Fetching $URL"
  curl -fL -o "$BUNDLE" "$URL"
  tar xzf "$BUNDLE" -C "$CACHE"
fi

cd "$LOCALNET_DIR"

# DB_PORT is the *host* mapping only; containers talk over the docker
# network. 15432 avoids colliding with a host postgres on 5432.
exec env IMAGE_TAG="$VERSION" LOCALNET_DIR="$LOCALNET_DIR" DB_PORT="${DB_PORT:-15432}" \
  docker compose \
  --env-file compose.env \
  --env-file env/common.env \
  --env-file env/postgres.env \
  --env-file env/splice.env \
  --profile sv --profile app-user --profile app-provider \
  up -d "$@"
