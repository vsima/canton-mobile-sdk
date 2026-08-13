// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "canton-mobile-sdk",
    platforms: [
        // Inherited from gRPC Swift 2, which requires these as minimums.
        .iOS(.v18),
        .macOS(.v15),
        .tvOS(.v18),
        .watchOS(.v11),
        .visionOS(.v2),
    ],
    products: [
        .library(name: "CantonKit", targets: ["CantonKit"]),
        .library(name: "CantonWalletKit", targets: ["CantonWalletKit"]),
        .library(name: "CantonLedgerAPI", targets: ["CantonLedgerAPI"]),
        .library(name: "CantonDappKit", targets: ["CantonDappKit"]),
        .library(name: "CantonDappWalletKit", targets: ["CantonDappWalletKit"]),
        .library(name: "CantonDappLanKit", targets: ["CantonDappLanKit"]),
        .library(name: "CantonDappWCKit", targets: ["CantonDappWCKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/grpc/grpc-swift-2.git", from: "2.4.0"),
        .package(url: "https://github.com/grpc/grpc-swift-protobuf.git", from: "2.4.0"),
        .package(url: "https://github.com/grpc/grpc-swift-nio-transport.git", from: "2.9.0"),
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.0"),
    ],
    targets: [
        // Generated Ledger API messages and service clients. Regenerate with
        // `make generate`; do not edit by hand.
        .target(
            name: "CantonLedgerAPI",
            dependencies: [
                .product(name: "GRPCCore", package: "grpc-swift-2"),
                .product(name: "GRPCProtobuf", package: "grpc-swift-protobuf"),
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ],
            path: "swift/Sources/CantonLedgerAPI"
        ),
        // Hand-written ergonomic layer: connection management, auth, and
        // higher-level Canton workflows.
        .target(
            name: "CantonKit",
            dependencies: [
                "CantonLedgerAPI",
                .product(name: "GRPCCore", package: "grpc-swift-2"),
                .product(name: "GRPCNIOTransportHTTP2", package: "grpc-swift-nio-transport"),
            ],
            path: "swift/Sources/CantonKit"
        ),
        // Wallet-grade layer: external signing (device-held keys), party
        // onboarding, token standard, and scan reads.
        .target(
            name: "CantonWalletKit",
            dependencies: ["CantonKit", "CantonLedgerAPI"],
            path: "swift/Sources/CantonWalletKit"
        ),
        // CIP-0103 dApp API, dApp side. Deliberately depends on NOTHING
        // else in this package: a dApp linking it has no business pulling in
        // the Ledger API stubs, signing drivers or the token standard.
        .target(
            name: "CantonDappKit",
            path: "swift/Sources/CantonDappKit"
        ),
        // Wallet side of the same protocol: dispatch, per-peer grants and the
        // approval seams. Signing and submission live in CantonWalletKit,
        // which is exactly why the dApp-side target does not depend on it.
        .target(
            name: "CantonDappWalletKit",
            dependencies: ["CantonDappKit", "CantonWalletKit"],
            path: "swift/Sources/CantonDappWalletKit"
        ),
        // LAN gRPC transport for the dApp API: JSON-RPC frames over a bidi
        // stream. Depends only on CantonDappKit (+ grpc) — the transport
        // carries both roles and must not pull in the wallet stack.
        .target(
            name: "CantonDappLanKit",
            dependencies: [
                "CantonDappKit",
                .product(name: "GRPCCore", package: "grpc-swift-2"),
                .product(name: "GRPCNIOTransportHTTP2", package: "grpc-swift-nio-transport"),
            ],
            path: "swift/Sources/CantonDappLanKit"
        ),
        // WalletConnect transport for the dApp API, wallet side: CAIP encoding
        // and CIP-0103 frame routing into a DappRequestHandler. Like the Kotlin
        // :canton-dapp-wc, it pulls in NO WalletConnect client library — a Reown
        // WalletKit delegate in the app drives its two touch-points. So it
        // depends only on CantonDappKit.
        .target(
            name: "CantonDappWCKit",
            dependencies: ["CantonDappKit"],
            path: "swift/Sources/CantonDappWCKit"
        ),
        .testTarget(
            name: "CantonKitTests",
            dependencies: [
                "CantonKit",
                "CantonLedgerAPI",
                .product(name: "GRPCCore", package: "grpc-swift-2"),
                .product(name: "GRPCProtobuf", package: "grpc-swift-protobuf"),
                .product(name: "GRPCNIOTransportHTTP2", package: "grpc-swift-nio-transport"),
            ],
            path: "swift/Tests/CantonKitTests"
        ),
        .testTarget(
            name: "CantonWalletKitTests",
            dependencies: ["CantonWalletKit"],
            path: "swift/Tests/CantonWalletKitTests"
        ),
        .testTarget(
            name: "CantonDappKitTests",
            dependencies: ["CantonDappKit"],
            path: "swift/Tests/CantonDappKitTests"
        ),
        .testTarget(
            name: "CantonDappWalletKitTests",
            dependencies: ["CantonDappWalletKit"],
            path: "swift/Tests/CantonDappWalletKitTests"
        ),
        .testTarget(
            name: "CantonDappLanKitTests",
            dependencies: ["CantonDappLanKit", "CantonDappWalletKit"],
            path: "swift/Tests/CantonDappLanKitTests"
        ),
        // Drives the adapter against a real DappSession, so it needs the
        // wallet-side engine as well as the transport under test.
        .testTarget(
            name: "CantonDappWCKitTests",
            dependencies: ["CantonDappWCKit", "CantonDappWalletKit"],
            path: "swift/Tests/CantonDappWCKitTests"
        ),
    ]
)
