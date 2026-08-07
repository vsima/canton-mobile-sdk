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

## Status

The harness is not yet wired into CI. The plan: boot the simple topology in a
scheduled workflow and run the same golden scenarios (see `testdata/`) through
both the Swift and Kotlin SDKs against it.
