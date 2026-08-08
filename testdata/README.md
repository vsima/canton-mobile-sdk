# Shared test vectors

Golden files that BOTH SDKs must satisfy, so the Swift and Kotlin
implementations cannot drift apart silently.

Planned layout (one directory per area, JSON vectors + expected outputs):

```
testdata/
├── commands/      # command payloads → expected serialized Commands messages
├── values/        # Daml values ↔ native type codec round-trips
└── events/        # raw transaction events → expected decoded models
```

## values/

`values/vectors.txt` — golden Daml `Value` encodings (base64-serialized
protos), regenerated with `tools/generate-value-vectors.sh`. Both SDKs'
golden-vector tests decode every vector with the typed readers and re-encode
it with the builders; a vector either SDK doesn't handle fails that SDK's
test, so coverage can't drift apart.

When adding a vector, wire it into `swift/Tests` and `kotlin/canton-sdk/src/test`
in the same PR.

## preparedtx/

`preparedtx/vectors.txt` — real `PreparedTransaction` protos (base64) with
the `prepared_transaction_hash` a live Canton participant returned for them
(Splice LocalNet, hashing scheme V2). Each SDK's prepared-transaction hasher
must recompute every hash byte-for-byte (Kotlin:
`canton-wallet-sdk`'s `PreparedTransactionHashGoldenTest`). To regenerate,
run `LocalNetPreparedTransactionHashIntegrationTest` against LocalNet and
copy its `golden-vector:` output lines. The byte-level algorithm spec lives
in `docs/prepared-tx-hash.md`.
