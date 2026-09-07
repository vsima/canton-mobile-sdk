# Roadmap

The goal is wallet-grade parity with the official TypeScript
[`@canton-network/wallet-sdk`](https://github.com/canton-network/wallet) —
natively, on the canonical gRPC Ledger API, with device-held keys.
Coming from the TS SDK? There's a
[method-level migration map](migrating-from-wallet-sdk.md).

## Shipped

Both SDKs cover the full core ledger workflow — connections, auth, command
submission with dedup, gap-free state sync — and a wallet layer that runs
end-to-end against live networks: external party onboarding,
externally-signed transactions with default-on hash verification and
completion tracking, CIP-0056 holdings, transfers and preapprovals, and
Scan reads. Device-held keys are hardware-verified at every tier — Secure
Enclave, StrongBox, and TEE. The checklist below records exactly what's
proven and where.

**Core layer**

- [x] Command submission with deduplication and automatic retry (`submitAndWait`, `submitAndWaitForTransaction`)
- [x] Update streams (`AsyncSequence`/`Flow`) with reconnect and offset resumption
- [x] Daml value builders + typed readers, held to shared golden vectors in `testdata/values/`
- [x] Network.framework transport (NIOTS) on Apple platforms
- [x] ACS bootstrap (`activeContractsSnapshot` + update stream = gap-free state sync)
- [x] Integration harness in CI (both SDKs against a live Canton node)
- [x] Typed errors decoding Canton's `google.rpc` details (code, correlation id, retry hints)
- [x] Pluggable TLS trust (both SDKs): `TlsTrust` pins the Ledger API
      connection to an operator's certificate authority, so interception by
      a CA the device happens to trust is rejected — with a matching
      `okHttpClient()` / `urlSession()` so the off-ledger REST clients get
      the same anchors instead of quietly staying on system trust.
      Authorities only, never leaves: leaf pins break on someone else's
      renewal, and Apple's Network.framework transport exposes no
      verification callback that could enforce one. No default pins ship.
      Held to a real TLS server in both suites, where the case that matters
      is the *system-default* client being rejected by the same server a
      pinned client reaches — see [docs/tls-trust.md](tls-trust.md)
- [x] Async access-token provider with expiry-aware caching and stream
      auth recovery (both SDKs, live-verified): `accessTokenProvider` may
      suspend — an OIDC refresh belongs in it directly — and
      `CachingTokenProvider` serves from cache until 30s before the JWT
      `exp` claim, so the provider runs once per token lifetime instead of
      once per RPC. Auth-terminated `updates()` streams refetch and
      reconnect exactly when the provider yields a *different* token —
      re-armed only after the server held a connection past the healthy
      window — so an expired token heals invisibly while bad credentials
      still fail fast. Semantics pinned by live probing: expiry is rejected
      on admission as `UNAUTHENTICATED` with no RetryInfo (that recovery
      path is live-tested end to end in both SDKs); mid-stream expiry
      enforcement is deployment-specific, and Canton's
      `ACCESS_TOKEN_EXPIRED` abort is treated as recoverable too
- [x] Maven Central + first tagged release (`v0.1.0`)

**Wallet layer**

- [x] `SigningDriver` abstraction; software Ed25519/P-256 drivers; Secure
      Enclave driver on Apple platforms (P-256, biometric-gated)
- [x] External party onboarding (`GenerateExternalPartyTopology` →
      `AllocateExternalParty`), live-verified with Ed25519 **and** EC P-256 keys
- [x] Externally-signed transactions via the Interactive Submission Service
      (prepare → sign → execute), live-verified end-to-end with P-256
- [x] Client-side re-computation/verification of the prepared-transaction hash
      (don't trust the node's hash blindly), live-verified in both SDKs
      (#16/#17), and completion tracking: `signAndExecuteAndWait` awaits the
      ledger's completion event and surfaces the update id/offset — or the
      typed rejection — instead of returning at execute-accepted
- [x] Secure Enclave driver verified on physical hardware (iPhone XR):
      enclave-resident key signs with Canton's exact encodings and its
      handle round-trips through `dataRepresentation`. Also passes
      tool-hosted on Apple Silicon Macs (`SecureEnclaveIntegrationTests`),
      so ordinary CI exercises the enclave; the sample app carries an
      on-device self-check (`EnclaveSelfCheck`, drive via `devicectl`)
- [x] Android Keystore driver (`canton-wallet-android`,
      `AndroidKeystoreSigningDriver`): attempts StrongBox, falls back to the
      TEE-backed keystore, and reports the achieved security level honestly.
      Hardware-verified on a OnePlus Open (Android 16): TEE-resident P-256
      key signs with Canton's encodings, reloads by alias, deletes cleanly
- [x] StrongBox branch verified on a Samsung Galaxy Z Flip5:
      `requireStrongBox = true` lands the key in the dedicated secure
      element (`SecurityLevel.STRONGBOX`, no silent TEE fallback) and signs
      with Canton's encodings — device-held keys are now hardware-verified
      at every tier: Secure Enclave, StrongBox, and TEE
- [x] Full wallet loop on StrongBox hardware, verified on a Pixel 11 Pro
      Fold (Android 17): a fresh party onboarded with a StrongBox-resident
      key, then the complete live flow against Splice LocalNet: faucet
      funding, WalletConnect pairing with an AI agent (canton-agent-mcp),
      per-request approval sheets, and a token-standard transfer signed in
      the secure element
- [x] CIP-0056 token standard client, first slice: holdings and the
      pending-instruction inbox (interface-filtered ACS reads), two-step
      transfers (create / accept / reject / withdraw) built from registry
      choice contexts, disclosed contracts, and external signing
- [x] Token standard verified against a live Amulet registry (Splice
      LocalNet): tap → real holdings decoded → registry transfer factory →
      offer lands in the receiver's inbox → accept signed by the P-256
      driver → holdings transferred. `integration/run-localnet.sh` boots
      the environment; the loop lives in `LocalNetTokenStandardIntegrationTest`
- [x] Transfer preapprovals (live-verified on LocalNet): an external party
      requests its own preapproval — externally signed — the validator
      automation accepts and pays, and transfers to it settle in one step
      (registry routes "direct", nothing lands in the inbox); scan lookup
      via `transferPreapprovalByParty`
- [x] Preapproval cancel (live-verified on LocalNet and a physical TEE
      device): the receiver archives its own `TransferPreapproval`
      unilaterally via `cancelTransferPreapproval` — externally signed, no
      registry context needed
- [x] Read layer, first slice (live-verified on LocalNet): parsed holdings
      history from ACS-delta update streams, ANS name resolution and DSO
      party via the Scan API
- [x] Transfer-level history semantics (live-verified on LocalNet, both
      SDKs): `holdingsHistory` rows now carry a `TransferSummary` —
      direction (sent / received / self / internal / unknown), counterparty,
      the signed fee-inclusive net amount, and the memo riding on the
      standard `reason` metadata key — derived from TransferInstruction
      interface views plus a genesis-built cid map that resolves archived
      holdings, so a wallet renders "sent 5 CC to alice — Invoice #4021"
      instead of raw UTXO deltas
- [x] Custody hook and persistence: `DelegatingSigningDriver` adapts any
      external signer (Fireblocks, BitGo, HSMs) to the driver interface via
      two async callbacks; `WalletStore` persists party ↔ key-handle
      bindings, with in-memory, Apple Keychain, and Android implementations
- [x] `AndroidKeystoreWalletStore`: records encrypted under an Android
      Keystore AES-GCM key, with Jetpack DataStore owning the file — atomic
      writes, one writer per file, never on the main thread. Deliberately
      not `EncryptedSharedPreferences`, deprecated since `security-crypto`
      1.1.0-alpha07. An unreadable store (the keystore key gone after a
      device restore, an altered file) raises instead of resetting to
      empty, because a wallet that silently forgets its party onboards a
      second identity. Note what this does and doesn't do: hardware key
      handles are keystore aliases, so the signing key never leaves the
      TEE either way — encrypting the file protects the binding's
      integrity and keeps the party id out of plain storage. Encoding is
      unit-tested in CI; the keystore and DataStore paths are covered by
      instrumentation tests, green on an API 36 emulator — durability
      asserted by decrypting the file independently of the store, plus
      ciphertext-not-plaintext on disk, an altered file, a deleted keystore
      key, and concurrent writers
- [x] Scan holdings summaries (live-verified on LocalNet, both SDKs):
      `ScanClient.holdingsSummary` reads server-side aggregated balances
      from Scan's periodic ACS snapshots (`/v1/holdings/summary`,
      `at_or_before` matching, migration id auto-resolved) — so apps don't
      fold the full ACS client-side. Snapshot-lagged by design; the result
      carries the answering snapshot's record time
- [x] SDK-level DevNet taps (live-verified on LocalNet, both SDKs):
      `ValidatorClient` wraps the validator's user-facing wallet API —
      onboarding (`userStatus` / `register`) and the test-network faucet
      (`tap`), which mints the requested **USD** value as Amulet (converted
      at the open mining round's price, like the validator wallet) and
      returns the minted contract id. The integration harness now drives
      its taps through this public surface instead of private duplicates
- [x] Transfer fee preview (live-verified on LocalNet, both SDKs):
      `ScanClient.amuletRulesConfig` decodes the published USD fee schedule
      (resolving the config schedule the way the ledger does) plus
      synchronizer traffic pricing, `ScanClient.openMiningRounds` reads the
      rounds' amulet price, and the pure `TransferFeeEstimator` reproduces
      Splice's `chargeSteppedRate` tranche semantics — held to exact
      expected values on the historical MainNet schedule in unit tests. On
      today's networks the governance-zeroed config (CIP-0078/0107) makes
      every estimate 0, and the LocalNet run proves it honestly: a real
      transfer's sender net equals −(amount + estimate) through the
      history API
- [x] Traffic purchase (live-verified on LocalNet, both SDKs):
      `ValidatorClient.buyTraffic` creates the validator wallet API's
      buy-traffic request — traffic goes to the participant hosting the
      given party, paid from the authenticated user's Amulet —
      `buyTrafficStatus` polls it to completed/failed, and
      `ScanClient.memberTrafficStatus` (with `partyParticipantId`) shows
      the purchased bytes landing; the live test buys the network's
      minimum top-up and watches the totals grow by it

**dApp layer (CIP-0103)**

- [x] Protocol core, both SDKs: types, JSON-RPC 2.0 codec and EIP-1474
      errors written against the OpenRPC document at `info.version` 0.5.0 —
      fetched, not transcribed, which is how three errors in our own notes
      surfaced (there is no `statusChanged` event, `LedgerApiRequest` has no
      `headers` field, and `prepareExecute` returns null with the outcome
      arriving via `txChanged`). Held to 35 positive and 9 negative golden
      vectors in `testdata/dapp/`, shared by both platforms; the asserted
      property is that decode-then-encode is a *fixpoint*, which catches the
      dropped optionals and stray nulls a decode-only test sails past
- [x] `DappClient` and the wallet-side `DappSession` provider engine, with
      an in-process transport binding them — the same transport a host app
      uses to embed the wallet layer while still coding against the standard
      API, so moving to an external wallet later is a transport swap
- [x] The rule the proxy design rests on: **a dApp may request an `actAs`,
      it may not choose one.** Naming a party outside the peer's grant is
      `4100`, never a silent substitution. Grants are per-session, so two
      dApps cannot see each other's accounts; an approval delegate cannot
      widen a grant beyond what the wallet offered; ledger access tokens are
      never handed to a dApp; and `ledgerApi` defaults to a read-only policy
      that also excludes user- and party-management, because read-only is
      not the same as harmless
- [x] The prepare → verify → sign → execute pipeline
      (`JsonPrepareExecutePipeline`): a dApp's `prepareExecute` proxies its
      commands inside the wallet's envelope, decodes the prepared
      transaction, and hands it to the existing `signAndExecuteAndWait` —
      hash verified, signed via the driver, executed, completion awaited,
      no transcode anywhere. Live-verified end-to-end on LocalNet with a
      real token-standard transfer
- [x] A **LAN gRPC transport** (`canton-dapp-lan` / `CantonDappLanKit`): one
      CIP-0103 session across a real bidirectional socket between two apps on
      the same network — the `DappRequestHandler` seam it forced is the one
      WalletConnect reuses. Proved by a byte-truncation mutation test;
      plaintext for now, TLS the remaining piece
- [x] A **WalletConnect transport adapter** (`canton-dapp-wc` / `CantonDappWCKit`):
      the Canton half of a WalletConnect session over that same seam — CIP-0103
      frames routed into the engine, plus the CAIP-10 encoding a Canton party
      needs (its `::` and any `_` are illegal in a WalletConnect account, so it
      is percent-encoded into the address). It depends on **no WalletConnect
      client library**: a Reown WalletKit binding drives it through two pure
      touch-points (`sessionNamespaces` + `handle`), so the adapter is unit-tested
      against a real `DappSession` with no relay. The Reown relay binding and
      wallet approval UI now ship in the reference wallets, live-verified on both
      (Android on-device, iOS on the simulator)
- [x] **`signMessage` domain separation**: signatures are over a 38-byte
      domain-prefixed message (`CantonNetwork:CIP-0103:signMessage:v1`), so a
      sign-in signature can never also be a valid transaction signature —
      byte-exact across platforms via a shared golden vector, checked with
      real crypto over both Ed25519 and P-256
- [x] **Bounded autonomy for agents** (`DappSpendPolicy`, `SpendLedger`,
      `DappActivity`): per-peer hard caps decided by the wallet before any
      sheet (per-transaction, rolling 24h per instrument from receipts,
      instrument and receiver allowlists, request rate), an optional
      auto-approve line (off by default; anything unparseable or not a plain
      token-standard transfer always goes to the approver), the check and
      the receipt under one per-session lock, and every outcome, approver
      asked or not, reported to the wallet. Live-verified in the example
      wallets: 1 CC auto-approved, 5 CC sent to the approver, 100 CC
      refused. [docs/agent-spend-policy.md](agent-spend-policy.md)
- [x] **The dApp's deadline reaches the approver**: `DappRequestContext`
      carries the WalletConnect envelope's expiry through the handler to
      `approve(request, context)`, so an approver that defers its answer
      can run on the dApp's own deadline; defaults keep existing handlers
      and approvers compiling
- [x] **Exactly-once WalletConnect requests**: clients re-emit a pending
      request and the relay can redeliver one; the adapter answers each
      `(topic, requestId)` once, so a redelivered payment never reaches the
      approver twice. `connect` is idempotent for an already-granted peer

## Next

- **DevNet registry run.** The SDK-level tap shipped (`ValidatorClient`,
  below); still pending is running the full token-standard loop against a
  DevNet registry, which needs DevNet validator credentials.
- **TLS + QR pairing for the LAN transport.** The LAN gRPC transport has
  shipped (above), plaintext; what remains is a TLS stream paired by QR for
  two devices. Deliberately no relay, so the honest limit is that a dApp
  reaches a wallet on the same device or the same network — not a wallet on
  cellular. (The reference apps in `canton-mobile-app` also demonstrate a
  self-describing `canton-checkout:` deep link — a camera-openable
  scan-to-pay QR that opens the wallet prefilled.)

## Exploring

- **Custody-provider integrations.** `DelegatingSigningDriver` already
  adapts any external signer; first-party drivers (Fireblocks raw
  signing, BitGo) land once they can be verified against real provider
  accounts.
- **CIP-0112 / Token Standard V2.** Tracking the next token-standard
  iteration — including provider-side preapproval renewal — as it
  stabilizes.

Deliberately out of scope: a JSON Ledger API fallback transport. The classic
HTTP/2-hostility that motivates JSON fallbacks is a browser problem; native
sockets (Network.framework on Apple, OkHttp on Android) don't hit it, and a
second protocol would double the parity surface of everything above. We'll
revisit only on evidence — transport-level failures attributable to specific
carriers or MDM-managed networks.
