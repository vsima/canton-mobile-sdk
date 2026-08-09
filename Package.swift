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
    ]
)
