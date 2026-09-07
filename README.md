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
> live-verified end-to-end. The dApp layer (CIP-0103) has shipped its protocol,
> client, provider engine, and three transports — in-process, LAN gRPC, and
> WalletConnect (the last live-verified on both reference wallets). See the
> [feature matrix](#feature-matrix) and [roadmap](#roadmap). Expect breaking
> changes before 1.0.

## Feature matrix

Capability-level comparison with Digital Asset's TypeScript
[`@canton-network/wallet-sdk`](https://www.npmjs.com/package/@canton-network/wallet-sdk)
("JS"). For the method-level mapping, see the
[migration map](docs/migrating-from-wallet-sdk.md). ✅ shipped · — not offered.

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
| Holdings history | ✅ | ✅ | ✅ | Transfer-level rows — direction, counterparty, signed fee-inclusive net amount, memo — live-verified on LocalNet |
| ANS / DSO reads | ✅ | ✅ | ✅ | `ScanClient`: ANS name resolution, DSO party |
| Scan holdings summaries | — | ✅ | ✅ | `ScanClient.holdingsSummary`: server-side aggregates from Scan's ACS snapshots (per-snapshot lag, not real-time); JS folds holdings client-side |
| DevNet taps | ✅ | ✅ | ✅ | `ValidatorClient.tap` mints via the validator's wallet API, live-verified on LocalNet; the DevNet-registry run still needs DevNet credentials |
| Traffic purchase | ✅ | ✅ | ✅ | `ValidatorClient.buyTraffic` + `buyTrafficStatus` (validator wallet API), traffic status via `ScanClient.memberTrafficStatus` — full buy → completed → status-reflects loop live-verified on LocalNet |
| Transfer fee preview | — | ✅ | ✅ | Typed AmuletRules + open-round reads plus a pure `TransferFeeEstimator` (Splice's stepped-rate semantics); on current networks fees are zero by governance (CIP-0078), which the LocalNet run verifies against a real transfer. JS exposes the raw config only |
| dApp connectivity (CIP-0103) | ✅ | ✅ | ✅ | The dApp client, the wallet-side provider engine, an in-process transport, a **LAN gRPC transport** (`canton-dapp-lan` / `CantonDappLanKit`), a **WalletConnect transport adapter** (`canton-dapp-wc` / `CantonDappWCKit`), the prepare → verify → sign → execute pipeline, and **`signMessage` domain separation** have shipped — held to golden vectors from OpenRPC 0.5.0 that both platforms satisfy, and live-verified end-to-end on LocalNet. The **WalletConnect native binding** (the Reown WalletKit relay I/O and wallet approval UI that drive the adapter) is built in the reference wallets and live-verified on both — Android on-device, iOS on the simulator — the path an *unmodified web* dApp has to a mobile wallet. The LAN and in-process transports carry no relay by design (same device or same network); WalletConnect reaches a wallet over its public relay. JS ships dApp connectivity as a separate, browser-only `@canton-network/dapp-sdk` |
| Agent spend policy (wallet-side) | — | ✅ | ✅ | `DappSpendPolicy` per peer: hard caps the wallet refuses on its own (per-transaction, rolling daily from receipts, instrument and receiver allowlists, request rate), plus an optional auto-approve line under which a transfer is approved without asking the approver; every outcome reported as `DappActivity`; the dApp's request deadline carried to the approver; redelivered WalletConnect requests answered exactly once. Live-verified in the example wallets (canton-mobile-app). See [docs/agent-spend-policy.md](docs/agent-spend-policy.md). JS has no wallet-side equivalent (its dApp SDK is the asking side) |
| TLS trust / certificate pinning | — | ✅ | ✅ | `TlsTrust` pins the Ledger API connection to an operator's CA (and the REST clients with it); JS leaves trust to the runtime, which a browser cannot configure at all. Leaf/SPKI pinning is deliberately not offered — see [docs/tls-trust.md](docs/tls-trust.md) |
| Transport | JSON | gRPC | gRPC | JS speaks the JSON Ledger API; the native SDKs speak the canonical gRPC Ledger API every participant serves |

## Libraries

**Eight Kotlin artifacts on Maven Central; one Swift package with seven
products.** Take the highest layer you need — each one brings the layers
below it, so depending on the wallet layer gives you the ergonomic layer and
the generated bindings too.

The dApp libraries are the exception, and deliberately so: they sit *beside*
the stack rather than on top of it, so an app that only wants to talk to a
wallet never links the ledger stubs, the signing drivers or the token
standard.

### Swift

Add the package once; `import` only the products you use.

| Product | What it gives you | Builds on |
|---|---|---|
| `CantonLedgerAPI` | Generated Ledger API messages and service clients. Regenerated wholesale from the vendored protos — never hand-edited. | — |
| `CantonKit` | The ergonomic layer: connections, JWT auth with automatic refresh, TLS trust pinning, command submission with dedup, gap-free state sync, typed errors. | `CantonLedgerAPI` |
| `CantonWalletKit` | The wallet layer: signing drivers (Secure Enclave, software, custody), external party onboarding, prepared-transaction hash verification, the CIP-0056 token standard, Scan and validator reads. | `CantonKit` |
| `CantonDappKit` | **dApp side** of CIP-0103: protocol types, JSON-RPC codec, `DappClient`, the transport protocol. Depends on nothing else in the package. | — |
| `CantonDappWalletKit` | **Wallet side** of CIP-0103: `DappSession`, per-peer account grants, the approval delegate, in-process transport. | `CantonDappKit`, `CantonWalletKit` |
| `CantonDappLanKit` | LAN gRPC transport for CIP-0103 — one session across a real socket, no relay. | `CantonDappKit` |
| `CantonDappWCKit` | WalletConnect transport adapter for CIP-0103 — drives the request handler over a WalletConnect session (your app supplies the Reown client). | `CantonDappKit` |

### Kotlin

All under the `io.github.vsima.canton` group.

| Artifact | What it gives you | Builds on |
|---|---|---|
| `canton-ledger-api` | Generated gRPC bindings for `com.daml.ledger.api.v2`, on the protobuf **lite** runtime. | — |
| `canton-sdk` | The ergonomic layer — the Kotlin peer of `CantonKit`. Plain JVM, so it runs server-side unchanged. | `canton-ledger-api` |
| `canton-wallet-sdk` | The wallet layer — the peer of `CantonWalletKit`. | `canton-sdk` |
| `canton-wallet-android` | The only Android-specific artifact: `AndroidKeystoreSigningDriver` (StrongBox / TEE-backed keys) and an encrypted DataStore-backed wallet store. | `canton-wallet-sdk` |
| `canton-dapp` | **dApp side** of CIP-0103. JSON and coroutines only — deliberately *not* `canton-sdk`. | — |
| `canton-dapp-wallet` | **Wallet side** of CIP-0103. | `canton-dapp`, `canton-wallet-sdk` |
| `canton-dapp-lan` | LAN gRPC transport for CIP-0103 — one session across a real socket, no relay. | `canton-dapp` |
| `canton-dapp-wc` | WalletConnect transport adapter for CIP-0103 — drives the request handler over a WalletConnect session (your app supplies the Reown client). | `canton-dapp` |

### Which do I need?

| I am building… | Swift | Kotlin |
|---|---|---|
| A read-only or backend integration | `CantonKit` | `canton-sdk` |
| A wallet with device-held keys | `CantonWalletKit` | `canton-wallet-sdk` (+ `canton-wallet-android` on Android) |
| A dApp that talks to someone else's wallet | `CantonDappKit` | `canton-dapp` |
| A wallet that accepts dApp connections | `CantonDappWalletKit` | `canton-dapp-wallet` |

Both SDKs are generated from the **same vendored protos** (pinned in
[`proto/UPSTREAM_VERSION`](proto/UPSTREAM_VERSION)) and released in lockstep:
version `X.Y.Z` of the Swift SDK and the Kotlin SDK always target the same
Canton release.

## Requirements

| | Swift | Kotlin |
|---|---|---|
| Minimum OS | iOS 18, macOS 15, tvOS 18, watchOS 11, visionOS 2 ¹ | Android API 26+ ² / any JVM 17+ |
| Toolchain | Swift 6 / Xcode 16+ | JDK 17+, Gradle 9 |
| Transport | [gRPC Swift 2](https://github.com/grpc/grpc-swift-2) over Network.framework | [gRPC Kotlin](https://github.com/grpc/grpc-kotlin) + OkHttp |

¹ Inherited from gRPC Swift 2, which supports these platforms as minimums.

² `canton-sdk` and everything above it use `java.time` and
`java.util.Base64`, which are API 26 on Android. This SDK deliberately does
**not** enable core-library desugaring — it would pull a shim into every
consumer to paper over a floor that Android 8 (2017) already clears. The
dApp modules `canton-dapp` and `canton-dapp-wallet` use neither and would run
lower, but ship against the same floor for consistency.

## Installation

Pick your product/artifact from [Libraries](#libraries) above — the examples
below use the ergonomic layer.

### Swift Package Manager

One package, however many products you import.

```swift
dependencies: [
    .package(url: "https://github.com/vsima/canton-mobile-sdk.git", from: "0.6.0"),
],
targets: [
    .target(name: "MyApp", dependencies: [
        .product(name: "CantonKit", package: "canton-mobile-sdk"),
        // Wallet apps add:  .product(name: "CantonWalletKit", …)
        // dApps add:        .product(name: "CantonDappKit", …)
    ]),
]
```

Or in Xcode: **File → Add Package Dependencies…** and paste the repo URL.

### Gradle

Each layer brings the ones below it, so name only the highest you need.

```kotlin
dependencies {
    implementation("io.github.vsima.canton:canton-sdk:0.6.0")
    // Wallet apps:          canton-wallet-sdk  (+ canton-wallet-android on Android)
    // dApps:                canton-dapp
    // Wallets taking dApp connections: canton-dapp-wallet
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

## Compatibility

| SDK version | Canton release | Ledger API |
|---|---|---|
| 0.1.x – 0.6.x | 3.5.11 – 3.5.12 | `com.daml.ledger.api.v2` |

## Repository layout

```
canton-mobile-sdk/
├── Package.swift            # SPM manifest (must live at the repo root)
├── proto/                   # Canton Ledger API protos, vendored at a pinned release
├── buf.yaml / buf.gen.yaml  # proto workspace + Swift codegen pipeline
├── swift/
│   ├── Sources/CantonLedgerAPI/       # generated — never edited by hand
│   ├── Sources/CantonKit/             # ergonomic layer (auth, connections, workflows)
│   ├── Sources/CantonWalletKit/       # wallet layer (signing drivers, external parties)
│   ├── Sources/CantonDappKit/         # CIP-0103, dApp side
│   ├── Sources/CantonDappWalletKit/   # CIP-0103, wallet side
│   ├── Sources/CantonDappLanKit/      # CIP-0103, LAN gRPC transport
│   └── Sources/CantonDappWCKit/       # CIP-0103, WalletConnect transport
├── kotlin/
│   ├── canton-ledger-api/             # generated bindings module (protoc at build time)
│   ├── canton-sdk/                    # ergonomic layer
│   ├── canton-wallet-sdk/             # wallet layer
│   ├── canton-wallet-android/         # Android Keystore driver + encrypted store
│   ├── canton-dapp/                   # CIP-0103, dApp side
│   ├── canton-dapp-wallet/            # CIP-0103, wallet side
│   ├── canton-dapp-lan/               # CIP-0103, LAN gRPC transport
│   └── canton-dapp-wc/                # CIP-0103, WalletConnect transport
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

## Roadmap

The goal is wallet-grade parity with the official TypeScript
[`@canton-network/wallet-sdk`](https://github.com/canton-network/wallet),
natively, on the canonical gRPC Ledger API, with device-held keys. What has
shipped, with where each piece is proven, what is next, and what is being
explored: [docs/roadmap.md](docs/roadmap.md). Coming from the TS SDK? There
is a [method-level migration map](docs/migrating-from-wallet-sdk.md).

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
