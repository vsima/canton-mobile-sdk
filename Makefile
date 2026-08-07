.PHONY: sync-protos codegen-plugins generate build test check-generated

# Re-sync vendored protos from the pinned canton release (or VERSION=x.y.z).
sync-protos:
	tools/sync-protos.sh $(VERSION)

# Build the protoc codegen plugins at the versions pinned in
# tools/codegen-plugins/Package.resolved.
codegen-plugins:
	swift build -c release --package-path tools/codegen-plugins --product protoc-gen-swift
	swift build -c release --package-path tools/codegen-plugins --product protoc-gen-grpc-swift-2

# Regenerate the checked-in Swift stubs from proto/.
generate: codegen-plugins
	buf generate

# Fail if the checked-in Swift stubs are stale (used by CI).
check-generated: generate
	git diff --exit-code swift/Sources/CantonLedgerAPI/Generated

build:
	swift build
	cd kotlin && ./gradlew build

test:
	swift test
	cd kotlin && ./gradlew test
