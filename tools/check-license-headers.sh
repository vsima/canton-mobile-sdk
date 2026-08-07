#!/usr/bin/env bash
set -euo pipefail

# Every hand-written source file must open with the SPDX license header:
#
#   // Copyright (c) 2026 Victor Sima
#   // SPDX-License-Identifier: Apache-2.0
#
# Generated code is exempt: swift/Sources/CantonLedgerAPI is stamped by the
# codegen pipeline, and the Kotlin bindings are generated at build time.
# Pass --fix to prepend the header to any file missing it.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
YEAR=2026
HEADER_LINE1="// Copyright (c) ${YEAR} Victor Sima"
HEADER_LINE2="// SPDX-License-Identifier: Apache-2.0"

fix=false
[ "${1:-}" = "--fix" ] && fix=true

sources() {
  find \
    "$ROOT/swift/Sources/CantonKit" \
    "$ROOT/swift/Sources/CantonWalletKit" \
    "$ROOT/swift/Tests" \
    -name '*.swift' 2>/dev/null
  find \
    "$ROOT/kotlin/canton-sdk/src" \
    "$ROOT/kotlin/canton-wallet-sdk/src" \
    -name '*.kt' 2>/dev/null
}

missing=0
while IFS= read -r file; do
  if ! head -3 "$file" | grep -q "SPDX-License-Identifier: Apache-2.0"; then
    if $fix; then
      printf '%s\n%s\n\n' "$HEADER_LINE1" "$HEADER_LINE2" | cat - "$file" > "$file.tmp"
      mv "$file.tmp" "$file"
      echo "added header: ${file#"$ROOT"/}"
    else
      echo "missing license header: ${file#"$ROOT"/}"
      missing=1
    fi
  fi
done < <(sources)

if [ "$missing" -ne 0 ]; then
  echo "run tools/check-license-headers.sh --fix" >&2
  exit 1
fi
