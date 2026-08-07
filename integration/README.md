# Integration test harness

Utilities for running both SDKs against a real Canton node.

## Local Canton

```sh
integration/run-canton.sh
```

Downloads the canton open-source release pinned in `proto/UPSTREAM_VERSION`
(override with `CANTON_VERSION=x.y.z`) into `integration/.cache/` and starts
the release's `01-simple-topology` example: one participant connected to a
local synchronizer. Ledger API ports are defined in the example's
`simple-topology.conf`.

For a full Canton Network stack (validator, Canton Coin, scan), see
[cn-quickstart](https://github.com/digital-asset/cn-quickstart) instead — it
is heavier but closer to production topology.

The example topology serves `participant1`'s Ledger API on
`localhost:5011` (plaintext, no auth).

## Running the SDK integration tests

Both SDKs ship live-ledger tests that are skipped unless `CANTON_LEDGER_PORT`
is set:

```sh
CANTON_LEDGER_PORT=5011 swift test --filter CantonLedgerIntegrationTests
cd kotlin && CANTON_LEDGER_PORT=5011 ./gradlew :canton-sdk:test --rerun
```

CI runs these weekly and on demand via the `integration` workflow
(`workflow_dispatch`).
