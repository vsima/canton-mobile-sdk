// swift-tools-version: 6.0

// Helper package whose only job is to build the protoc codegen plugins
// (protoc-gen-swift, protoc-gen-grpc-swift-2) at pinned versions.
// Built by `make codegen-plugins`; binaries land in .build/release and are
// referenced as local plugins from buf.gen.yaml. Exact versions are locked
// by the committed Package.resolved.
import PackageDescription

let package = Package(
    name: "codegen-plugins",
    platforms: [.macOS(.v15)],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.0"),
        .package(url: "https://github.com/grpc/grpc-swift-protobuf.git", from: "2.4.0"),
    ]
)
