# Bounded autonomy for agents

An AI agent paired with a wallet over CIP-0103 can ask for anything; the
wallet decides what it gets. This layer lets a wallet draw two lines per
peer, applied before its approver is asked:

- **what the peer may never do**: hard caps the session refuses on its own;
- **what it may do without asking**: an optional amount under which a
  transfer that passes every cap is approved without consulting the
  approver.

Everything between the lines goes to the approver as before, and every
outcome, whether the approver was asked or not, is reported to the wallet
through an observer. The SDK ships the decisions and the reporting; what a
wallet shows for them is its own. An example implementation with a UI is
the wallet in [canton-mobile-app](https://github.com/vsima/canton-mobile-app).

## The policy

`DappSpendPolicy` is a value the wallet supplies per peer, read fresh on
every request:

| Field | Meaning | Default |
|---|---|---|
| `maxPerTransaction` | Largest single transfer | no cap |
| `dailyCap` | Largest total per instrument in any rolling 24-hour window, computed from receipts | no cap |
| `allowedInstruments` | Instruments the peer may move | any |
| `allowedReceivers` | Parties the peer may pay | any |
| `minRequestInterval` | Least time between two transaction requests from the peer | none |
| `autoApproveBelow` | Amount under which a transfer that passes every cap is approved without asking the approver | off |

For each `prepareExecute` / `prepareExecuteAndWait` the session reads the
submission strictly as a single Token Standard `TransferFactory_Transfer`
(`DappCommandSummary.transferOf`) and decides:

1. **Refuse** when the instrument or receiver is not allowed, the amount
   exceeds `maxPerTransaction`, or the amount plus the last 24 hours of
   receipts for that instrument exceeds `dailyCap`. The dApp gets
   `4001 USER_REJECTED` with `"spend policy: <reason>"`; the approver is
   not asked.
2. **Auto-approve** when `autoApproveBelow` is set and the amount is under
   it (and rule 1 passed). The approver is not asked.
3. **Ask** otherwise: the approver decides, as for any transaction.

The policy fails closed. Anything it cannot read as a plain transfer, a
non-positive amount, a submission with more than one command, or a
transfer with an unparseable amount, is always sent to the approver and
never auto-approved. `minRequestInterval` violations are refused as
rate-limited before the transfer is parsed.

The check and the receipt that follows it are one critical section per
session (a submission lock), so two requests arriving together cannot both
pass a daily cap that only one of them fits under.

## Receipts

Executed transfers are appended to a `SpendLedger` as `SpendReceipt`s
(peer, time, instrument, amount, receiver, command id, whether it was
auto-approved). `dailyCap` is computed from `receiptsSince(peerId, since)`.
`InMemorySpendLedger` is the default and starts empty on every launch; a
wallet whose caps must hold across launches supplies a ledger that
persists. A ledger that cannot be read fails the request rather than
assuming nothing was spent.

## Activity

Every session outcome is reported to an optional `DappActivityObserver` as
a `DappActivity` (peer, time, kind, the transfer summary when there is one,
a detail line): `connected`, `connectionDeclined`, `messageSigned`,
`messageDeclined`, `transactionRequested`, `transactionAutoApproved`,
`transactionRefused`, `transactionRateLimited`, `transactionDeclined`,
`transactionExecuted`, `transactionFailed`. An observer that throws never
breaks a request. Three kinds happen without any approver call, auto-
approved, refused, and rate-limited; the observer is the only way a wallet
learns of them, so they are the ones it should make visible.

## The dApp's clock

A JSON-RPC frame carries no deadline, but the WalletConnect envelope does.
The transport passes it as `DappRequestContext(expiresAt)` through
`DappRequestHandler.handle(request, context)` to
`DappApprovalDelegate.approve(request, context)`. An approver that defers
its answer can then run on the dApp's own deadline instead of a guessed
one. Both new methods have defaults that forward to the old ones; a
handler or approver with no use for the context implements only the other.

## Exactly once, and idempotent connect

WalletConnect clients re-emit a still-pending request (on reconnect, on
foreground, right after a respond) and the relay can redeliver one. The
WalletConnect adapter answers each `(topic, requestId)` exactly once: a
duplicate waits for, or reuses, the first answer instead of reaching the
engine, and the approver, a second time.

Agents call `connect` before every request to make sure they hold
accounts. For a peer that is already connected and granted, `connect`
returns the existing grant without asking the approver again.

## Wiring it

Swift:

```swift
let session = DappSession(
    peer: peer,
    accounts: accounts,
    approver: approver,                       // implements approve(_:context:)
    network: network,
    messageSigner: signer,
    prepareExecutePipeline: pipeline,
    spendPolicy: { policyStore.policy(for: peer.id) },   // read on every request
    spendLedger: receiptsStore,               // your SpendLedger; in-memory by default
    activityObserver: { activity in activityLog.append(activity) }
)
```

Kotlin:

```kotlin
val session = DappSession(
    peer = peer,
    accounts = accounts,
    approver = approver,                      // overrides approve(request, context)
    network = network,
    messageSigner = signer,
    prepareExecute = pipeline,
    spendPolicy = { policyStore.policy(peer.id) },
    spendLedger = receiptsStore,
    activityObserver = { activity -> activityLog.append(activity) },
)
```

With `DappSpendPolicy(maxPerTransaction: 10, dailyCap: 25, autoApproveBelow: 2)`,
a 1 CC transfer is auto-approved, a 5 CC transfer goes to the approver,
and a 100 CC transfer is refused. That is the policy the live runs used.

## What the agent sees

| Outcome | Wire result | Approver asked |
|---|---|---|
| Under `autoApproveBelow`, within caps | executed | no |
| Within caps, at or above the line | executed, or `4001` with the approver's reason | yes |
| Over a cap, wrong instrument or receiver | `4001` `"spend policy: …"` | no |
| Too soon after the last request | `4001` rate-limited | no |
| Not a plain transfer, or unparseable | the approver decides | yes |
| Unanswered past the dApp's deadline | whatever the approver returns for it | yes |

## What this is not

- The policy belongs to the wallet. A dApp cannot read it, set it, or
  learn where the lines are except by hitting them.
- `autoApproveBelow` is off by default. Turning it on makes the session a
  signer for that peer under that amount, so a wallet should key the
  policy to an identity it trusts. The SDK identifies a peer by the
  `DappPeer` the wallet builds, including a `verified` flag the transport
  can set from the Verify API; whether an unverified peer may carry an
  auto-approve line is the wallet's decision, not enforced here.
- No timeouts on the approver itself: how long an answer may take is the
  wallet's decision, informed by the dApp's deadline above.
