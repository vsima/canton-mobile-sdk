#!/usr/bin/env bash
set -euo pipefail

# Regenerates testdata/values/vectors.txt — golden Daml Value encodings that
# BOTH SDKs' codec tests must decode and re-encode identically. Each line is
# "<name> <base64-of-serialized-com.daml.ledger.api.v2.Value>".
# Requires protoc.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/testdata/values/vectors.txt"
PROTO_ROOT="$ROOT/proto/ledger-api-value"
VALUE_PROTO="$PROTO_ROOT/com/daml/ledger/api/v2/value.proto"

encode() {
  printf '%s' "$2" \
    | protoc --encode=com.daml.ledger.api.v2.Value -I "$PROTO_ROOT" "$VALUE_PROTO" \
    | base64
}

mkdir -p "$(dirname "$OUT")"
{
  echo "# Golden Daml Value vectors. Regenerate with tools/generate-value-vectors.sh"
  echo "# Every SDK's golden-vector test must handle every line; unknown names fail the test."
  echo "unit $(encode unit 'unit {}')"
  echo "bool_true $(encode bool 'bool: true')"
  echo "int64 $(encode int64 'int64: 42')"
  echo "date $(encode date 'date: 19700')"
  echo "timestamp $(encode timestamp 'timestamp: 1700000000000000')"
  echo "numeric $(encode numeric 'numeric: "3.1415926535"')"
  echo "party $(encode party 'party: "alice::122abc"')"
  echo "text $(encode text 'text: "hello, canton"')"
  echo "contract_id $(encode contract_id 'contract_id: "00deadbeef"')"
  echo "optional_none $(encode optional_none 'optional {}')"
  echo "optional_some_text $(encode optional_some 'optional { value { text: "present" } }')"
  echo "list_int64 $(encode list 'list { elements { int64: 1 } elements { int64: 2 } elements { int64: 3 } }')"
  echo "record_amount $(encode record 'record { fields { label: "value" value { numeric: "100.0" } } fields { label: "currency" value { text: "USD" } } }')"
  echo "variant_left_int64 $(encode variant 'variant { constructor: "Left" value { int64: 1 } }')"
  echo "enum_red $(encode enum 'enum { constructor: "Red" }')"
} > "$OUT"

echo "Wrote $(grep -c '^[^#]' "$OUT") vectors to $OUT"
