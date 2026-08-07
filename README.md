# Canton Mobile SDK

**Native Swift and Kotlin SDKs for the [Canton Network](https://www.canton.network/) Ledger API.**

[![swift](https://github.com/vsima/canton-mobile-sdk/actions/workflows/swift.yml/badge.svg)](https://github.com/vsima/canton-mobile-sdk/actions/workflows/swift.yml)
[![kotlin](https://github.com/vsima/canton-mobile-sdk/actions/workflows/kotlin.yml/badge.svg)](https://github.com/vsima/canton-mobile-sdk/actions/workflows/kotlin.yml)
[![android](https://github.com/vsima/canton-mobile-sdk/actions/workflows/android.yml/badge.svg)](https://github.com/vsima/canton-mobile-sdk/actions/workflows/android.yml)
[![protos](https://github.com/vsima/canton-mobile-sdk/actions/workflows/protos.yml/badge.svg)](https://github.com/vsima/canton-mobile-sdk/actions/workflows/protos.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Canton](https://img.shields.io/badge/Canton-3.5.11-6f42c1)](https://github.com/digital-asset/canton/releases/tag/v3.5.11)

Build iOS and Android apps that talk directly to a Canton participant node
over gRPC — command submission, transaction streams, active contracts, and
JWT-authenticated connections — with idiomatic, strongly-typed APIs on each
platform.

> ⚠️ **Early days.** The generated Ledger API bindings are complete, but the
> ergonomic layer currently covers connection management, auth, and version
> negotiation. See the [roadmap](#roadmap) for what's next. Expect breaking
> changes before 1.0.

## Packages

| Platform | Package | Modules |
|---|---|---|
| iOS / macOS (Swift) | Swift Package Manager, this repo URL | `CantonKit` (ergonomic layer), `CantonLedgerAPI` (generated bindings) |
| Android / JVM (Kotlin) | Maven Central *(coming soon)* | `io.github.vsima.canton:canton-sdk`, `io.github.vsima.canton:canton-ledger-api` |

Both SDKs are generated from the **same vendored protos** (pinned in
[`proto/UPSTREAM_VERSION`](proto/UPSTREAM_VERSION)) and released in lockstep:
version `X.Y.Z` of the Swift SDK and the Kotlin SDK always target the same
Canton release.

## Requirements

| | Swift | Kotlin |
|---|---|---|
| Minimum OS | iOS 18, macOS 15, tvOS 18, watchOS 11, visionOS 2 ¹ | Android API 21+ / any JVM 17+ |
| Toolchain | Swift 6 / Xcode 16+ | JDK 17+, Gradle 9 |
| Transport | [gRPC Swift 2](https://github.com/grpc/grpc-swift-2) (SwiftNIO) | [gRPC Kotlin](https://github.com/grpc/grpc-kotlin) + OkHttp |

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

*(Not yet published to Maven Central — until the first release, build locally
with `cd kotlin && ./gradlew publishToMavenLocal`.)*

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

## Repository layout

```
canton-mobile-sdk/
├── Package.swift            # SPM manifest (must live at the repo root)
├── proto/                   # Canton Ledger API protos, vendored at a pinned release
├── buf.yaml / buf.gen.yaml  # proto workspace + Swift codegen pipeline
├── swift/
│   ├── Sources/CantonLedgerAPI/   # generated — never edited by hand
│   └── Sources/CantonKit/         # ergonomic layer (auth, connections, workflows)
├── kotlin/
│   ├── canton-ledger-api/         # generated bindings module (protoc at build time)
│   └── canton-sdk/                # ergonomic layer
├── examples/
│   └── android/                   # sample app; CI builds debug + R8 release
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

## Compatibility

| SDK version | Canton release | Ledger API |
|---|---|---|
| 0.1.x (unreleased) | 3.5.11 | `com.daml.ledger.api.v2` |

## Roadmap

- [ ] Command submission with deduplication + completion correlation
- [ ] Transaction stream decoding with reconnect / offset resumption
- [ ] Daml value ↔ native type codecs (shared golden vectors in `testdata/`)
- [ ] Network.framework transport (NIOTS) on Apple platforms
- [ ] Integration harness in CI (both SDKs against a live Canton node)
- [ ] JSON Ledger API fallback transport for proxy-hostile networks
- [ ] Maven Central + first tagged release

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
