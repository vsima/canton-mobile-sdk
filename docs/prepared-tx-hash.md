# Canton Prepared-Transaction Hash — Hashing Scheme V2 (byte-level spec)

Spec for client-side recomputation of `PrepareSubmissionResponse.prepared_transaction_hash`
from the `PreparedTransaction` proto returned by the Ledger API Interactive Submission
Service (`com.daml.ledger.api.v2.interactive`). Written so a Swift port can be done from
this document alone. Verified byte-for-byte against a live Canton participant (Splice
LocalNet, Canton 3.x, `hashing_scheme_version = HASHING_SCHEME_VERSION_V2`).

## References (all read while writing this; the byte layout below mirrors them exactly)

Authoritative server implementation (digital-asset/canton, `main`):

- `community/base/src/main/scala/com/digitalasset/canton/protocol/hash/TransactionHash.scala`
  — `tryHashTransactionWithMetadata`: final hash = purpose + scheme byte + txHash + metadataHash.
  https://github.com/digital-asset/canton/blob/main/community/base/src/main/scala/com/digitalasset/canton/protocol/hash/TransactionHash.scala
- `.../hash/TransactionHashBuilder.scala` — prepends purpose then the scheme-version byte.
- `.../hash/VersionedTransactionHasher.scala` — transaction-level encoding: purpose,
  serialization version string, root count, per-root node hashes.
- `.../hash/NodeHashBuilderCommon.scala` — field order for Create/Fetch/Exercise/Rollback
  nodes, and the V2 "not supported" guards (contract keys, byKey, QueryByKey).
- `.../hash/v2/NodeHashBuilder.scala` — the `0x01` Node Encoding Version byte (V2 only).
- `.../hash/v2/TransactionMetadataHasher.scala` — metadata field order and the `0x01`
  Metadata Encoding Version byte (V2 only).
- `.../hash/NodeHashBuilder.scala` — node tags: Create=0, Exercise=1, Fetch=2, Rollback=3,
  QueryByKey=4 (QueryByKey unsupported in V2); V2 supports LF serialization version <= V1.
- `.../hash/LfValueHashBuilder.scala` — Daml value type tags 0x00..0x0f and per-type encoding;
  `addIdentifier` = packageId + dotted module segments + dotted entity segments.
- `community/base/src/main/scala/com/digitalasset/canton/crypto/HashBuilder.scala`
  (`HashBuilderFromMessageDigest`) — primitive encodings: `addInt` (4B big-endian),
  `addLong` (8B big-endian), `addString`/`addByteString` (int32 length prefix + raw),
  `addBool` (1 byte), `addOptional` (presence byte), `addHash`/`addLfHash` (raw, NO length
  prefix), `addStringSet` (sorted + count prefix), `addPurpose` (int32 of the purpose id).
- `community/base/src/main/scala/com/digitalasset/canton/serialization/DeterministicEncoding.scala`
  — `encodeInt`/`encodeLong` are fixed-length **big-endian** (`ByteOrder.BIG_ENDIAN`), NOT varint.
- `community/base/src/main/scala/com/digitalasset/canton/crypto/HashPurpose.scala`
  — `HashPurpose(48, "PreparedSubmission")` → purpose prefix `00 00 00 30`.
- `community/ledger/ledger-api-core/src/main/scala/com/digitalasset/canton/platform/apiserver/services/command/interactive/codec/PreparedTransactionEncoder.scala`
  — the participant **pre-sorts** everything before writing the proto: signatories,
  stakeholders, acting_parties, choice_observers, act_as (sorted lexicographically), and
  input_contracts (sorted by contract id). The client therefore hashes lists in proto order.
  Also: values are emitted with `lfValueToApiValue(verbose = true)`, so record labels and
  record/variant/enum ids are populated.
- `community/ledger/ledger-api-core/.../ledger/api/validation/ValueValidator.scala`
  — normative decode at execute time: record-field `label` `""` ⇒ absent (None), non-empty
  ⇒ present. This defines the presence rule for the label byte (see gotchas).

Porting reference (Digital Asset TypeScript wallet SDK, `verifyTxHash` internals),
hyperledger-labs/splice-wallet-kernel (`main`, published as `@canton-network/wallet-sdk`):

- `sdk/wallet-sdk/src/wallet/namespace/utils/hash/util/encoder/preparedTransactionEncoder.ts`
- `sdk/wallet-sdk/src/wallet/namespace/utils/hash/util/encoder/transactionEncoder.ts`
- `sdk/wallet-sdk/src/wallet/namespace/utils/hash/util/encoder/metadataEncoder.ts`
- `sdk/wallet-sdk/src/wallet/namespace/utils/hash/util/encoder/ledgerApiValueEncoder.ts`
- `sdk/wallet-sdk/src/wallet/namespace/utils/hash/util/encoder/primitiveEncoder.ts`
- `sdk/wallet-sdk/src/wallet/namespace/utils/hash/util/encoder/collectionEncoder.ts`
- `sdk/wallet-sdk/src/wallet/namespace/utils/hash/util/const.ts` (purpose `00 00 00 30`,
  node encoding version `0x01`, scheme byte `0x02`)
- Standalone equivalent: `core/tx-visualizer/src/hashing_scheme_v2.ts`
  (https://github.com/hyperledger-labs/splice-wallet-kernel/blob/main/core/tx-visualizer/src/hashing_scheme_v2.ts)

Proto shape: `com/daml/ledger/api/v2/interactive/interactive_submission_service.proto`
(PreparedTransaction, Metadata, DamlTransaction) and
`com/daml/ledger/api/v2/interactive/transaction/v1/interactive_submission_data.proto`
(Create/Fetch/Exercise/Rollback nodes), vendored in this repo under `proto/ledger-api/`.

## Primitives

All hashing is SHA-256. All multi-byte integers are **fixed-length big-endian** (never
protobuf varints).

| primitive        | encoding                                                                 |
|------------------|--------------------------------------------------------------------------|
| `byte(b)`        | 1 raw byte                                                               |
| `bool(b)`        | 1 byte: `0x01` true, `0x00` false                                        |
| `int32(v)`       | 4 bytes big-endian (two's complement)                                    |
| `int64(v)`       | 8 bytes big-endian (two's complement)                                    |
| `bytes(b)`       | `int32(len(b))` ++ raw bytes (length prefix ALWAYS present)              |
| `string(s)`      | `bytes(utf8(s))`                                                         |
| `hex(s)`         | `bytes(hexDecode(s))` — contract ids arrive as lowercase hex strings     |
| `hash(h)`        | raw 32 bytes, **NO length prefix** (fixed size)                          |
| `seed(b)`        | raw seed bytes, **NO length prefix** (node seeds, 32 bytes)              |
| `optional(x, f)` | absent: `0x00`; present: `0x01` ++ `f(x)`                                |
| `repeated(xs,f)` | `int32(count)` ++ `f(x0)` ++ `f(x1)` ++ …                                |

`purpose` = `int32(48)` = `00 00 00 30` (HashPurpose.PreparedSubmission).

uint64 proto fields (`preparation_time`, `created_at`, `min/max_ledger_effective_time`) are
hashed with `int64` (8B BE); values are micros-since-epoch and fit in a signed 64-bit int.
uint32 `mediator_group` is hashed with `int32`.

## Top level

```
finalHash = SHA256( purpose ++ 0x02 ++ transactionHash ++ metadataHash )
```

- `0x02` is the hashing-scheme-version byte (`HASHING_SCHEME_VERSION_V2 = 2`, hashed as ONE
  byte, not an int32).
- `transactionHash` and `metadataHash` are raw 32-byte SHA-256 outputs (no length prefixes).

## Transaction hash

Over `PreparedTransaction.transaction` (`DamlTransaction`):

```
transactionHash = SHA256(
  purpose
  ++ string(transaction.version)              // e.g. "2.1"
  ++ int32(len(roots))
  ++ nodeHash(roots[0]) ++ nodeHash(roots[1]) ++ …   // 32 raw bytes each
)
```

`nodeHash(nodeId)`: look up the node with `node_id == nodeId` in `transaction.nodes`
(error if missing), then `SHA256(encodeNode(node))`.

Node seeds: `DamlTransaction.node_seeds` is a list of `{node_id: int32, seed: bytes}`.
`DamlTransaction.Node.node_id` is a *string* — match by `nodeSeed.node_id.toString() == node.node_id`.

`encodeNode`: the node must be the `v1` variant of the versioned_node oneof (error
otherwise). Dispatch on the v1 node_type oneof:

### Create node (tag 0x00)

```
0x01                                  // Node Encoding Version (V2 scheme only)
++ string(lf_version)                 // e.g. "2.1"
++ 0x00                               // Create node tag
++ optional(nodeSeed, seed)           // presence byte + RAW seed bytes (no length prefix)
++ hex(contract_id)
++ string(package_name)
++ identifier(template_id)
++ value(argument)
++ repeated(signatories, string)
++ repeated(stakeholders, string)
```

- For creates inside the transaction the seed MUST exist (error if missing) but is still
  encoded through the optional wrapper (`0x01` ++ seed). For input-contract creates
  (metadata) there is no seed: encode `0x00`.
- `key` set ⇒ error: contract keys are not hashable under V2
  (`NodeHashingError.UnsupportedFeature` server-side; V2 supports LF serialization <= V1).

### Exercise node (tag 0x01)

```
0x01                                  // Node Encoding Version
++ string(lf_version)
++ 0x01                               // Exercise node tag
++ seed(nodeSeed)                     // REQUIRED, raw bytes, NO presence byte, NO length prefix
++ hex(contract_id)
++ string(package_name)
++ identifier(template_id)
++ repeated(signatories, string)
++ repeated(stakeholders, string)
++ repeated(acting_parties, string)
++ optional(interface_id, identifier) // proto message-field presence
++ string(choice_id)
++ value(chosen_value)
++ bool(consuming)
++ optional(exercise_result, value)   // proto message-field presence
++ repeated(choice_observers, string)
++ int32(len(children))
++ nodeHash(children[0]) ++ …         // recursive: 32 raw bytes per child
```

- Missing seed ⇒ error. `key` set or `by_key == true` ⇒ error (V2).
- NOTE the field order differs from Create: parties come BEFORE choice data, and
  signatories/stakeholders come before acting_parties.

### Fetch node (tag 0x02)

```
0x01                                  // Node Encoding Version
++ string(lf_version)
++ 0x02                               // Fetch node tag
++ hex(contract_id)
++ string(package_name)
++ identifier(template_id)
++ repeated(signatories, string)
++ repeated(stakeholders, string)
++ optional(interface_id, identifier)
++ repeated(acting_parties, string)
```

- No seed at all. `key` set or `by_key == true` ⇒ error (V2).

### Rollback node (tag 0x03)

```
0x01                                  // Node Encoding Version
++ 0x03                               // Rollback node tag  (NO lf_version — rollback has none)
++ int32(len(children))
++ nodeHash(children[0]) ++ …
```

### QueryByKey node

Unsupported under V2 ⇒ error.

## Identifier encoding

`Identifier {package_id, module_name, entity_name}` (module/entity are dotted names):

```
identifier(id) = string(package_id)
              ++ repeated(split(module_name, '.'), string)
              ++ repeated(split(entity_name, '.'), string)
```

No length prefix around the whole identifier.

## Daml value encoding (`com.daml.ledger.api.v2.Value`)

Every value is prefixed with a 1-byte type tag (collision prevention):

| tag  | type       | payload after the tag                                                        |
|------|------------|------------------------------------------------------------------------------|
| 0x00 | Unit       | (nothing)                                                                    |
| 0x01 | Bool       | `bool(v)`                                                                    |
| 0x02 | Int64      | `int64(v)`                                                                   |
| 0x03 | Numeric    | `string(v)` — the proto's decimal string verbatim, no re-normalization       |
| 0x04 | Timestamp  | `int64(micros)`                                                              |
| 0x05 | Date       | `int32(days)`                                                                |
| 0x06 | Party      | `string(v)`                                                                  |
| 0x07 | Text       | `string(v)`                                                                  |
| 0x08 | ContractId | `hex(v)`                                                                     |
| 0x09 | Optional   | `optional(inner_value, value)` — presence = proto `Optional.value` field set |
| 0x0a | List       | `repeated(elements, value)`                                                  |
| 0x0b | TextMap    | `repeated(entries, e -> string(e.key) ++ value(e.value))`                    |
| 0x0c | Record     | `optional(record_id, identifier) ++ repeated(fields, recordField)`           |
| 0x0d | Variant    | `optional(variant_id, identifier) ++ string(constructor) ++ value(value)`    |
| 0x0e | Enum       | `optional(enum_id, identifier) ++ string(constructor)`                       |
| 0x0f | GenMap     | `repeated(entries, e -> value(e.key) ++ value(e.value))`                     |

`recordField(f) = optional(label, string) ++ value(f.value)` where **presence of `label`
is `label != ""`** (proto3 string field; Canton's ValueValidator maps `""` ⇒ None). In
practice prepared transactions are verbose-encoded so labels are always non-empty, but the
empty-string rule is the normative one.

`record_id`/`variant_id`/`enum_id`/`interface_id` presence = proto message-field presence
(`hasRecordId()` etc.).

Map/list orderings: hash entries in proto order (TextMap arrives key-sorted from the
participant; GenMap arrives in its LF insertion order).

An unset value oneof (`SUM_NOT_SET`) ⇒ error.

## Metadata hash

Over `PreparedTransaction.metadata`:

```
metadataHash = SHA256(
  purpose
  ++ 0x01                                        // Metadata Encoding Version (V2 scheme only)
  ++ repeated(submitter_info.act_as, string)
  ++ string(submitter_info.command_id)
  ++ string(transaction_uuid)
  ++ int32(mediator_group)
  ++ string(synchronizer_id)
  ++ optional(min_ledger_effective_time, int64)   // proto3 `optional uint64` presence
  ++ optional(max_ledger_effective_time, int64)
  ++ int64(preparation_time)
  ++ int32(len(input_contracts))
  ++ inputContract(input_contracts[0]) ++ …
)

inputContract(ic) = int64(ic.created_at)
                 ++ SHA256(encodeCreateNode(ic.v1, seed = absent))   // raw 32 bytes
```

- `ic.contract` must be the `v1` variant (a Create node message) ⇒ else error.
- The input-contract Create is encoded exactly like a transaction Create node but with the
  seed optional set to `0x00` (input contracts have no seeds).
- `event_blob` is NOT hashed. Deprecated `global_key_mapping` is NOT hashed.
- **`max_record_time` (Metadata field 11) is NOT part of the V2 hash** (it postdates V2;
  V2's `TransactionMetadataHasher` in `hash/v2/` stops at input contracts). Do not add it.

## Gotchas checklist (things that will silently break a port)

1. **Fixed-length big-endian ints everywhere** — never protobuf varints, never
   little-endian. `int32` also prefixes every byte/string length.
2. **Hashes and node seeds are raw** (no length prefix, no presence byte except where the
   optional wrapper is explicitly specified). Create seed = optional (presence byte + raw);
   Exercise seed = bare raw; Fetch/Rollback = no seed at all.
3. **Three different encoding-version bytes floating around**: the scheme byte `0x02`
   appears ONCE at the top level; the node encoding version `0x01` prefixes EVERY node
   (including input-contract creates); the metadata encoding version `0x01` appears once
   inside the metadata preimage. V3+ drops the latter two — this spec is V2 only.
4. **Purpose prefix `00 00 00 30` appears in all three preimages** (transaction, metadata,
   final) — 4 bytes each time.
5. **Rollback nodes have no lf_version string**; all other nodes hash it right after the
   node-encoding-version byte and BEFORE the node tag.
6. Exercise field order ≠ proto field order (parties before interface/choice; children
   last as hashed subtrees). Follow the order in this spec, not the proto.
7. Children/roots are hashed as **32-byte subtree hashes** with a count prefix — the child
   node bodies are never inlined into the parent preimage.
8. `contract_id` proto fields are hex STRINGS; decode to bytes first, then length-prefix.
   (First bytes are the contract-id version, e.g. `00` — do not strip anything.)
9. Optional presence must come from **proto wire presence** (`hasX()`), not from
   default-value checks — except record-field `label`, where presence = non-empty string.
10. Party lists (signatories/stakeholders/acting_parties/choice_observers/act_as) and
    input_contracts arrive **pre-sorted by the participant**; hash them in proto order.
    Do NOT re-sort client-side (server sorts before writing the proto; re-sorting is a
    no-op on honest input but would mask a malformed proto).
11. V2 rejects: contract keys (`key` set on Create/Fetch/Exercise), `by_key == true`,
    QueryByKey nodes, non-`v1` node/contract variants, missing seeds on transaction
    Create/Exercise nodes. Reject loudly — the server would compute a different hash or
    refuse at execute time.
12. Timestamps are already micros in the proto (`sfixed64`/`uint64`) — no unit conversion.
13. The final comparison target is `PrepareSubmissionResponse.prepared_transaction_hash`,
    raw 32 bytes. Check `hashing_scheme_version == HASHING_SCHEME_VERSION_V2 (2)` before
    verifying; refuse to verify other schemes rather than passing them through.
14. **Reject duplicate node ids and node-seed node ids** when building the
    `nodesById` / `seedsByNodeId` lookup maps ("duplicate node id 'X' in prepared
    transaction"), rather than letting map construction resolve the collision
    silently. Natural map constructors disagree on which duplicate wins (e.g. Kotlin's
    `associateBy` keeps the last entry, a Swift `Dictionary(uniquingKeysWith:)` is
    typically written to keep the first), so two implementations could hash two
    different payloads for the same malformed proto — exactly the last-wins vs
    first-wins divergence this check prevents. An honest participant never emits
    duplicates; failing verification is the only safe response.

## Kotlin implementation in this repo

`kotlin/canton-wallet-sdk/src/main/kotlin/io/github/vsima/canton/wallet/PreparedTransactionHash.kt`
(object `PreparedTransactionHash`: `compute(PreparedTransaction): ByteArray`,
`verify(PrepareSubmissionResponse)`), wired into
`InteractiveSubmissionClient.signAndExecute(verifyHash: Boolean = true)`.
Golden vector (real LocalNet prepare response + node hash): `testdata/preparedtx/vectors.txt`,
exercised by `PreparedTransactionHashGoldenTest` without a running ledger.
