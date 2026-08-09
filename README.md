# Canton Mobile SDK

**The native wallet stack for the [Canton Network](https://www.canton.network/) — Swift and Kotlin.**

The Swift/Kotlin peer of the official TypeScript
[`@canton-network/wallet-sdk`](https://www.npmjs.com/package/@canton-network/wallet-sdk):
everything a native app needs to talk to a Canton participant node, plus the
wallet-grade layer — external party onboarding and externally-signed
transactions with keys that never leave the device. Verified against a live
participant: external parties onboard **and transact** with EC P-256 keys,
the scheme Apple's Secure Enclave and Android StrongBox sign.

[![swift](https://github.com/vsima/canton-mobile-sdk/actions/workflows/swift.yml/badge.svg)](https://github.com/vsima/canton-mobile-sdk/actions/workflows/swift.yml)
[![kotlin](https://github.com/vsima/canton-mobile-sdk/actions/workflows/kotlin.yml/badge.svg)](https://github.com/vsima/canton-mobile-sdk/actions/workflows/kotlin.yml)
[![android](https://github.com/vsima/canton-mobile-sdk/actions/workflows/android.yml/badge.svg)](https://github.com/vsima/canton-mobile-sdk/actions/workflows/android.yml)
[![ios](https://github.com/vsima/canton-mobile-sdk/actions/workflows/ios.yml/badge.svg)](https://github.com/vsima/canton-mobile-sdk/actions/workflows/ios.yml)
[![protos](https://github.com/vsima/canton-mobile-sdk/actions/workflows/protos.yml/badge.svg)](https://github.com/vsima/canton-mobile-sdk/actions/workflows/protos.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Canton](https://img.shields.io/badge/Canton-3.5.12-6f42c1)](https://github.com/digital-asset/canton/releases/tag/v3.5.12)

Build iOS and Android apps that talk directly to a Canton participant node
over gRPC — command submission, transaction streams, active contracts, and
JWT-authenticated connections — with idiomatic, strongly-typed APIs on each
platform.

> ⚠️ **Early days.** The generated Ledger API bindings are complete and the
> ergonomic layer covers connections, auth, command submission with dedup,
> and gap-free state sync. The wallet layer — external signing, party
> onboarding, the CIP-0056 token standard client, Scan reads — is newer but
> live-verified end-to-end. See the [feature matrix](#feature-matrix) and
> [roadmap](#roadmap). Expect breaking changes before 1.0.

## Packages

| Platform | Package | Modules |
|---|---|---|
| iOS / macOS (Swift) | Swift Package Manager, this repo URL | `CantonWalletKit` (wallet layer), `CantonKit` (ergonomic layer), `CantonLedgerAPI` (generated bindings) |
| Android / JVM (Kotlin) | [Maven Central](https://central.sonatype.com/namespace/io.github.vsima.canton) | `io.github.vsima.canton:canton-wallet-sdk`, `:canton-sdk`, `:canton-ledger-api` |

Both SDKs are generated from the **same vendored protos** (pinned in
[`proto/UPSTREAM_VERSION`](proto/UPSTREAM_VERSION)) and released in lockstep:
version `X.Y.Z` of the Swift SDK and the Kotlin SDK always target the same
Canton release.

## Requirements

| | Swift | Kotlin |
|---|---|---|
| Minimum OS | iOS 18, macOS 15, tvOS 18, watchOS 11, visionOS 2 ¹ | Android API 21+ / any JVM 17+ |
| Toolchain | Swift 6 / Xcode 16+ | JDK 17+, Gradle 9 |
| Transport | [gRPC Swift 2](https://github.com/grpc/grpc-swift-2) over Network.framework | [gRPC Kotlin](https://github.com/grpc/grpc-kotlin) + OkHttp |

¹ Inherited from gRPC Swift 2, which supports these platforms as minimums.

## Installation

### Swift Package Manager

```swift
dependencies: [
    .package(url: "https://github.com/vsima/canton-mobile-sdk.git", from: "0.1.0"),
],
targets: [
    .target(name: "MyApp", dependencies: [
        .product(name: "CantonKit", package: "canton-mobile-sdk"),
    ]),
]
```

Or in Xcode: **File → Add Package Dependencies…** and paste the repo URL.

### Gradle

```kotlin
dependencies {
    implementation("io.github.vsima.canton:canton-sdk:0.1.0")
}
```

## Quickstart

Connect to a participant node and read the Ledger API version — with a JWT
provider for authenticated ledgers:

**Swift**

```swift
import CantonKit

let client = CantonClient(
    configuration: .init(
        host: "validator.example.com",
        port: 6865,
        accessTokenProvider: { try await myAuth.freshLedgerApiToken() }
    )
)
let version = try await client.ledgerApiVersion()
```

Anything the ergonomic layer doesn't cover yet is available through the
generated service clients:

```swift
try await client.withServices { services in
    let ledgerEnd = try await services.state.getLedgerEnd(.init())
    // services.grpc gives access to every other generated client
}
```

**Kotlin**

```kotlin
import io.github.vsima.canton.CantonClient
import io.github.vsima.canton.CantonClientConfiguration

val client = CantonClient(
    CantonClientConfiguration(
        host = "validator.example.com",
        port = 6865,
        accessTokenProvider = { myAuth.freshLedgerApiToken() },
    )
)
val version = client.ledgerApiVersion()
```

The full generated Ledger API surface (`com.daml.ledger.api.v2.*`) ships in
`canton-ledger-api` and works with any `ManagedChannel` you build.

### Self-custody: external parties and external signing

Onboard a party whose key lives on the device — in the Secure Enclave on
Apple platforms — and transact as it. The participant prepares transactions
and the driver signs their hashes; the node never holds the key and cannot
act for the party unilaterally:

```swift
import CantonWalletKit

let driver = try SecureEnclaveSigningDriver()   // P-256, enclave-resident
// (or SoftwareSigningDriver.generate(.ed25519) — both verified live)

let parties = ExternalPartyClient(client: client)
let party = try await parties.allocate(
    driver: driver,
    synchronizerId: synchronizer,
    partyHint: "alice"
)

let submission = InteractiveSubmissionClient(client: client)
let prepared = try await submission.prepare(
    commands: [createCommand], actAs: party.partyId, synchronizerId: synchronizer
)
try await submission.signAndExecute(
    prepared: prepared, driver: driver,
    partyId: party.partyId, keyFingerprint: party.publicKeyFingerprint
)
```

```kotlin
import io.github.vsima.canton.wallet.*

val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)

val parties = ExternalPartyClient(channel)
val party = parties.allocate(driver, synchronizerId, partyHint = "alice")

val submission = InteractiveSubmissionClient(channel)
val prepared = submission.prepare(listOf(createCommand), party.partyId, synchronizerId)
submission.signAndExecute(prepared, driver, party.partyId, party.publicKeyFingerprint)
```

Both flows run against a live Canton participant in CI
(`ExternalPartyIntegrationTest[s]`), with Ed25519 and EC P-256 keys.

### Submitting commands

Build commands with the generated types, submit through the client: the SDK
generates a stable command id, retries transient failures with backoff, and
lets the participant deduplicate — so a retried submission can never execute
twice:

```swift
let transaction = try await client.submitAndWaitForTransaction(
    CommandSubmission(commands: [createCommand], actAs: [party])
)
```

```kotlin
val transaction = client.submitAndWaitForTransaction(
    CommandSubmission(commands = listOf(createCommand), actAs = listOf(party))
)
```

### State sync: snapshot + stream

Bootstrap local state from the active contract set, then follow the update
stream from the snapshot's offset — no gaps, no duplicates:

```swift
let snapshot = try await client.activeContractsSnapshot(parties: [party])
// apply snapshot.contracts to local state ...
for try await update in client.updates(
    .init(parties: [party], beginExclusive: snapshot.offset)
) { /* deltas */ }
```

```kotlin
val snapshot = client.activeContractsSnapshot(listOf(party))
// apply snapshot.contracts to local state ...
client.updates(
    UpdateSubscription(parties = listOf(party), beginExclusive = snapshot.offset)
).collect { /* deltas */ }
```

### Streaming updates

Subscribe to committed ledger updates as an `AsyncSequence` / `Flow`. The SDK
reconnects on transient failures and resumes from the last received offset —
one uninterrupted, gap-free stream, which is exactly what flaky mobile
networks need:

```swift
for try await update in client.updates(
    .init(parties: [party], beginExclusive: try await client.ledgerEnd())
) {
    if case .transaction(let transaction) = update {
        // apply to local state; persist update.offset to resume next launch
    }
}
```

```kotlin
client.updates(
    UpdateSubscription(parties = listOf(party), beginExclusive = client.ledgerEnd())
).collect { update ->
    if (update is LedgerUpdate.Transaction) {
        // apply to local state; persist update.offset to resume next launch
    }
}
```

### Error handling

Failed calls throw a typed error decoded from Canton's structured
`google.rpc` details — error code, correlation id, and retry hints:

```swift
do {
    let version = try await client.ledgerApiVersion()
} catch let error as CantonError {
    if error.isRetryable {
        // schedule a retry after error.retryDelay (server-suggested backoff)
    }
    log.error("\(error.errorCode ?? "UNKNOWN") — correlation id \(error.correlationId ?? "n/a")")
}
```

```kotlin
try {
    client.ledgerApiVersion()
} catch (e: CantonException) {
    if (e.error.retryable) {
        // schedule a retry after e.error.retryDelay (server-suggested backoff)
    }
    log.error("${e.error.errorCode} — correlation id ${e.error.correlationId}")
}
```

## Repository layout

```
canton-mobile-sdk/
├── Package.swift            # SPM manifest (must live at the repo root)
├── proto/                   # Canton Ledger API protos, vendored at a pinned release
├── buf.yaml / buf.gen.yaml  # proto workspace + Swift codegen pipeline
├── swift/
│   ├── Sources/CantonLedgerAPI/   # generated — never edited by hand
│   ├── Sources/CantonKit/         # ergonomic layer (auth, connections, workflows)
│   └── Sources/CantonWalletKit/   # wallet layer (signing drivers, external parties)
├── kotlin/
│   ├── canton-ledger-api/         # generated bindings module (protoc at build time)
│   ├── canton-sdk/                # ergonomic layer
│   └── canton-wallet-sdk/         # wallet layer
├── examples/
│   ├── android/                   # sample app; CI builds debug + R8 release
│   └── ios/                       # sample app; xcodegen project, CI simulator build
├── testdata/                # golden vectors both SDKs must satisfy
├── integration/             # local Canton harness for end-to-end testing
└── tools/                   # proto sync + pinned codegen plugin builds
```

Design decisions worth knowing:

- **One proto source of truth.** `tools/sync-protos.sh` vendors the
  `ledger-api` proto roots from the official canton release bundle; both SDKs
  regenerate from it in the same PR. CI runs buf breaking-change checks.
- **Generated vs. hand-written is a hard boundary.** `CantonLedgerAPI` /
  `canton-ledger-api` are regenerated wholesale; all ergonomics live in
  `CantonKit` / `canton-sdk`.
- **Swift stubs are checked in** so SPM consumers never need `protoc`. The
  codegen plugins are built at pinned versions (`tools/codegen-plugins`)
  because the hosted buf plugin still targets gRPC Swift v1.
- **Kotlin uses the protobuf _lite_ runtime.** The full `protobuf-java`
  runtime duplicate-classes against the `protobuf-javalite` that AndroidX
  DataStore, Firebase, and friends already put on most Android classpaths —
  so this SDK ships lite-generated code (including its own `google.rpc`
  types) and never drags the full runtime into your app.
- **Lockstep releases.** A single `vX.Y.Z` tag releases both SDKs (SPM
  requires semver tags on the repo root).

## Development

Prerequisites: Xcode 16+ (Swift 6), JDK 17+, [`buf`](https://buf.build/docs/installation).

```sh
make build            # swift build + gradle build
make test             # both test suites
make generate         # rebuild codegen plugins + regenerate Swift stubs
make sync-protos VERSION=3.6.0   # bump the vendored Canton protos
make check-generated  # CI check: generated stubs match proto/
integration/run-canton.sh        # boot a local Canton participant + synchronizer
```

Bumping Canton: `make sync-protos VERSION=x.y.z && make generate`, review the
diff, and let the `protos` workflow flag wire-level breaking changes.

The Android sample (`examples/android`) is a composite build against the SDK
source — open it in Android Studio or build it with
`cd examples/android && ./gradlew :app:assembleRelease`. CI builds both the
debug and the R8-minified release variant, so shrinker regressions surface in
our PRs instead of in consumers' release builds.

The iOS sample (`examples/ios`) mirrors it: a SwiftUI app depending on
`CantonKit` from source. Generate the project with
`cd examples/ios && xcodegen generate`, then open it in Xcode or build with
`xcodebuild`. Both samples default to a local `integration/run-canton.sh`
ledger (`127.0.0.1` on the iOS simulator, `10.0.2.2` on the Android emulator).

## Compatibility

| SDK version | Canton release | Ledger API |
|---|---|---|
| 0.1.x – 0.5.x | 3.5.11 – 3.5.12 | `com.daml.ledger.api.v2` |

## Feature matrix

Capability-level comparison with Digital Asset's TypeScript
[`@canton-network/wallet-sdk`](https://www.npmjs.com/package/@canton-network/wallet-sdk)
("JS"). For the method-level mapping, see the
[migration map](docs/migrating-from-wallet-sdk.md). ✅ shipped ·
🔜 planned · — not offered.

| Capability | JS | Swift | Kotlin | Notes |
|---|:---:|:---:|:---:|---|
| Key generation & signing (Ed25519, EC P-256) | ✅ | ✅ | ✅ | Both schemes live-verified against Canton here |
| Hardware-resident keys | — | ✅ | ✅ | Secure Enclave (Swift), StrongBox / TEE keystore (Kotlin, `canton-wallet-android`) — all three tiers verified on physical devices |
| Custody-provider hook | ✅ | ✅ | ✅ | JS ships first-party drivers (Fireblocks, Blockdaemon, Securosys); here `DelegatingSigningDriver` adapts any external signer, first-party integrations are on the roadmap |
| External party onboarding | ✅ | ✅ | ✅ | Generate → sign → allocate in one call; live-verified with Ed25519 and P-256 |
| Externally-signed submission | ✅ | ✅ | ✅ | Prepare → sign → execute via the Interactive Submission Service; live-verified end-to-end |
| Client-side prepared-tx hash verification | ✅ | ✅ | ✅ | JS exposes `verifyTxHash` to call yourself; here `signAndExecute` verifies by default (opt out via `verifyHash`), held to shared golden vectors |
| Completion tracking | ✅ | ✅ | ✅ | `signAndExecuteAndWait` awaits the ledger's completion event and returns the update id/offset — or the typed rejection |
| CIP-0056 holdings, inbox & two-step transfers | ✅ | ✅ | ✅ | Live-verified against a live Amulet registry (Splice LocalNet) |
| Transfer preapprovals (request / lookup / cancel) | ✅ | ✅ | ✅ | Receiver-side cancel is native-only (in JS, removal is validator-operated); all three live-verified here |
| Holdings history | ✅ | ✅ | ✅ | First slice live-verified; transfer-level semantics matched to JS are next on the roadmap |
| ANS / DSO reads | ✅ | ✅ | ✅ | `ScanClient`: ANS name resolution, DSO party |
| DevNet taps | ✅ | 🔜 | 🔜 | The integration harness taps today; SDK-level API planned |
| Traffic purchase | ✅ | 🔜 | 🔜 | Traffic purchase and status/fee reads planned |
| dApp connectivity (CIP-0103) | ✅ | 🔜 | 🔜 | JS: separate `@canton-network/dapp-sdk`; exploring for native — see roadmap |
| Transport | JSON | gRPC | gRPC | JS speaks the JSON Ledger API; the native SDKs speak the canonical gRPC Ledger API every participant serves |

## Roadmap

The goal is wallet-grade parity with the official TypeScript
[`@canton-network/wallet-sdk`](https://github.com/canton-network/wallet) —
natively, on the canonical gRPC Ledger API, with device-held keys.
Coming from the TS SDK? There's a
[method-level migration map](docs/migrating-from-wallet-sdk.md).

### Shipped

Both SDKs cover the full core ledger workflow — connections, auth, command
submission with dedup, gap-free state sync — and a wallet layer that runs
end-to-end against live networks: external party onboarding,
externally-signed transactions with default-on hash verification and
completion tracking, CIP-0056 holdings, transfers and preapprovals, and
Scan reads. Device-held keys are hardware-verified at every tier — Secure
Enclave, StrongBox, and TEE. The checklist below records exactly what's
proven and where.

**Core layer**

- [x] Command submission with deduplication and automatic retry (`submitAndWait`, `submitAndWaitForTransaction`)
- [x] Update streams (`AsyncSequence`/`Flow`) with reconnect and offset resumption
- [x] Daml value builders + typed readers, held to shared golden vectors in `testdata/values/`
- [x] Network.framework transport (NIOTS) on Apple platforms
- [x] ACS bootstrap (`activeContractsSnapshot` + update stream = gap-free state sync)
- [x] Integration harness in CI (both SDKs against a live Canton node)
- [x] Typed errors decoding Canton's `google.rpc` details (code, correlation id, retry hints)
- [x] Maven Central + first tagged release (`v0.1.0`)

**Wallet layer**

- [x] `SigningDriver` abstraction; software Ed25519/P-256 drivers; Secure
      Enclave driver on Apple platforms (P-256, biometric-gated)
- [x] External party onboarding (`GenerateExternalPartyTopology` →
      `AllocateExternalParty`), live-verified with Ed25519 **and** EC P-256 keys
- [x] Externally-signed transactions via the Interactive Submission Service
      (prepare → sign → execute), live-verified end-to-end with P-256
- [x] Client-side re-computation/verification of the prepared-transaction hash
      (don't trust the node's hash blindly), live-verified in both SDKs
      (#16/#17), and completion tracking: `signAndExecuteAndWait` awaits the
      ledger's completion event and surfaces the update id/offset — or the
      typed rejection — instead of returning at execute-accepted
- [x] Secure Enclave driver verified on physical hardware (iPhone XR):
      enclave-resident key signs with Canton's exact encodings and its
      handle round-trips through `dataRepresentation`. Also passes
      tool-hosted on Apple Silicon Macs (`SecureEnclaveIntegrationTests`),
      so ordinary CI exercises the enclave; the sample app carries an
      on-device self-check (`EnclaveSelfCheck`, drive via `devicectl`)
- [x] Android Keystore driver (`canton-wallet-android`,
      `AndroidKeystoreSigningDriver`): attempts StrongBox, falls back to the
      TEE-backed keystore, and reports the achieved security level honestly.
      Hardware-verified on a OnePlus Open (Android 16): TEE-resident P-256
      key signs with Canton's encodings, reloads by alias, deletes cleanly
- [x] StrongBox branch verified on a Samsung Galaxy Z Flip5:
      `requireStrongBox = true` lands the key in the dedicated secure
      element (`SecurityLevel.STRONGBOX`, no silent TEE fallback) and signs
      with Canton's encodings — device-held keys are now hardware-verified
      at every tier: Secure Enclave, StrongBox, and TEE
- [x] CIP-0056 token standard client, first slice: holdings and the
      pending-instruction inbox (interface-filtered ACS reads), two-step
      transfers (create / accept / reject / withdraw) built from registry
      choice contexts, disclosed contracts, and external signing
- [x] Token standard verified against a live Amulet registry (Splice
      LocalNet): tap → real holdings decoded → registry transfer factory →
      offer lands in the receiver's inbox → accept signed by the P-256
      driver → holdings transferred. `integration/run-localnet.sh` boots
      the environment; the loop lives in `LocalNetTokenStandardIntegrationTest`
- [x] Transfer preapprovals (live-verified on LocalNet): an external party
      requests its own preapproval — externally signed — the validator
      automation accepts and pays, and transfers to it settle in one step
      (registry routes "direct", nothing lands in the inbox); scan lookup
      via `transferPreapprovalByParty`
- [x] Preapproval cancel (live-verified on LocalNet and a physical TEE
      device): the receiver archives its own `TransferPreapproval`
      unilaterally via `cancelTransferPreapproval` — externally signed, no
      registry context needed
- [x] Read layer, first slice (live-verified on LocalNet): parsed holdings
      history from ACS-delta update streams, ANS name resolution and DSO
      party via the Scan API
- [x] Custody hook and persistence: `DelegatingSigningDriver` adapts any
      external signer (Fireblocks, BitGo, HSMs) to the driver interface via
      two async callbacks; `WalletStore` persists party ↔ key-handle
      bindings, with in-memory and (Apple) Keychain implementations

### Next

- **Transfer-level history semantics.** Holdings history today parses
  ACS deltas; next is transfer-level semantics matched to the TS SDK's,
  held to shared golden vectors — so a wallet can render "sent 10 CC to
  alice" instead of raw UTXO deltas.
- **SDK-level DevNet taps.** The integration harness already taps test
  funds; promote that to a public API and run the full token-standard
  loop against a DevNet registry.
- **Traffic purchase and fee preview.** Buy synchronizer traffic for a
  party and preview fees before submitting.
- **Scan holdings summaries.** Aggregated balances from Scan's
  server-side snapshots, so apps don't fold the full ACS client-side.

### Exploring

- **Custody-provider integrations.** `DelegatingSigningDriver` already
  adapts any external signer; first-party drivers (Fireblocks raw
  signing, BitGo) land once they can be verified against real provider
  accounts.
- **CIP-0103 dApp connectivity.** Letting dApps drive a native wallet;
  revisited as the mobile transport story firms up.
- **CIP-0112 / Token Standard V2.** Tracking the next token-standard
  iteration — including provider-side preapproval renewal — as it
  stabilizes.

Deliberately out of scope: a JSON Ledger API fallback transport. The classic
HTTP/2-hostility that motivates JSON fallbacks is a browser problem; native
sockets (Network.framework on Apple, OkHttp on Android) don't hit it, and a
second protocol would double the parity surface of everything above. We'll
revisit only on evidence — transport-level failures attributable to specific
carriers or MDM-managed networks.

## Contributing

Issues and PRs welcome. A few ground rules:

1. Never edit generated code or `proto/` by hand — change the pipeline instead.
2. Feature parity: user-facing features should land for both SDKs in the same
   PR (or a linked pair), plus shared vectors in `testdata/` where applicable.
3. `make test` must pass on both platforms.

## License

[Apache-2.0](LICENSE).

This project is not affiliated with or endorsed by Digital Asset. *Canton*,
*Daml*, and the Canton Network are trademarks of their respective owners; the
vendored `.proto` files are © Digital Asset and distributed under their
upstream license.
