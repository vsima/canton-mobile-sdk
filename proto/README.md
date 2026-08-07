# Vendored Canton protos

These `.proto` files are vendored verbatim from the official Canton open-source
release bundle (`canton-open-source-<version>-protobuf.tar.gz`, attached to each
release at <https://github.com/digital-asset/canton/releases>). They are the
single source of truth for both the Swift and Kotlin SDKs.

- `UPSTREAM_VERSION` — the pinned canton release these protos came from.
- `ledger-api/` — the gRPC Ledger API (`com.daml.ledger.api.v2`), including the
  admin and interactive-submission services.
- `ledger-api-value/` — the Daml value encoding (`value.proto`), shipped as a
  separate proto root upstream.

## Updating

```sh
tools/sync-protos.sh 3.6.0   # or whatever the new canton release is
make generate                # regenerate Swift stubs (Kotlin generates at build time)
git diff                     # review; buf breaking checks also run in CI
```

Do not hand-edit anything in this directory except this README.

## google.rpc protos

`googleapis/` holds `google/rpc/status.proto` and `error_details.proto`,
vendored from [googleapis](https://github.com/googleapis/googleapis) at the
commit pinned in `googleapis/GOOGLEAPIS_REF`. The Ledger API references
`google.rpc.Status` (e.g. `Completion.status`), and Canton's rich errors use
the `error_details` types. They are compiled into both SDKs directly — the
pre-built `proto-google-common-protos` artifact only supports the full
protobuf runtime, and the Kotlin SDK uses protobuf **lite**.
