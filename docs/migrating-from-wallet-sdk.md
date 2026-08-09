# Coming from `@canton-network/wallet-sdk`

A method-level map from Digital Asset's TypeScript wallet SDK to the native
stack. The shape transfers directly: what the TS SDK calls a *controller*,
this SDK splits into small clients over the same underlying APIs — with one
structural difference: **the TS SDK talks to the JSON Ledger API; this SDK
talks to the canonical gRPC Ledger API** every participant serves.

Package mapping:

| TypeScript | Swift | Kotlin |
|---|---|---|
| `@canton-network/wallet-sdk` | `CantonWalletKit` | `io.github.vsima.canton:canton-wallet-sdk` |
| (ledger client underneath) | `CantonKit` | `io.github.vsima.canton:canton-sdk` |

## Keys and signing

| TS SDK | Native | Notes |
|---|---|---|
| `TopologyController.createNewKeyPair()` | `SoftwareSigningDriver.generate(.ed25519 / .ecP256)` | Both schemes live-verified against Canton |
| *(no equivalent)* | `SecureEnclaveSigningDriver()` (Swift) | Enclave-resident P-256 — verified on physical hardware; persist `dataRepresentation`, never key material |
| `signing-fireblocks` / `signing-blockdaemon` / `signing-securosys` drivers | `DelegatingSigningDriver(publicKeyProvider:signer:)` | Adapt any custody SDK with two async callbacks; convert raw `r‖s` ECDSA to DER |
| `wallet-store` / `wallet-store-sql` | `WalletStore` protocol; `InMemoryWalletStore`, `KeychainWalletStore` (Apple) | Party ↔ key-handle bindings; bring your own durable store in a page of code |

## Party onboarding

| TS SDK | Native |
|---|---|
| `LedgerController.generateExternalParty` + `allocateExternalParty`, or `signAndAllocateExternalParty` | `ExternalPartyClient.allocate(driver:synchronizerId:partyHint:userId:)` — generate → sign multi-hash → allocate in one call |
| `LedgerController.listSynchronizers` | `ExternalPartyClient.connectedSynchronizers()` |
| `TopologyController.createFingerprintFromPublicKey` | Not needed: the participant returns the canonical fingerprint during onboarding (`AllocatedExternalParty.publicKeyFingerprint`) |

## Externally-signed submission

| TS SDK | Native |
|---|---|
| `LedgerController.prepareSubmission` | `InteractiveSubmissionClient.prepare(commands:actAs:synchronizerId:...)` (accepts disclosed contracts) |
| `executeSubmission` / `prepareSignAndExecuteTransaction` | `InteractiveSubmissionClient.signAndExecute(prepared:driver:partyId:keyFingerprint:)` |
| `verifyTxHash` | Roadmap: client-side hash re-computation |

## Token standard (CIP-0056)

| TS SDK (`TokenStandardController`) | Native (`TokenStandardClient`) |
|---|---|
| `listHoldingUtxos` | `listHoldings(partyId:)` |
| `listHoldingTransactions` | `holdingsHistory(partyId:beginExclusive:endInclusive:)` — per-update rows with created/archived holdings plus a transfer-level `summary` (direction, counterparty, signed fee-inclusive net amount, memo) |
| `createTransfer` (+ registry context fetching) | `createTransfer(...)` — registry context, disclosed contracts, and external signing composed internally |
| `fetchPendingTransferInstructionView` | `pendingTransferInstructions(partyId:)` — the wallet inbox |
| `exerciseTransferInstructionChoice` (Accept/Reject/Withdraw) | `exerciseTransferInstruction(..., choice: .accept/.reject/.withdraw)` |
| `createTransferPreapprovalCommand` / `ValidatorController.externalPartyPreApprovalSetup` | `requestTransferPreapproval(driver:party:provider:dso:synchronizerId:)` — receiver-signed, so a self-custody party requests its own |
| `getTransferPreApprovalByParty` | `ScanClient.transferPreapprovalByParty(_:)` |
| *(no equivalent — removal is validator-operated)* | `cancelTransferPreapproval(...)` — the receiver archives its own preapproval, self-signed |
| `getInstrumentById` / `listInstruments` | Roadmap (registry metadata API) |
| `createTap` | Roadmap |
| `buyMemberTraffic` / `getMemberTrafficStatus` | Roadmap (traffic work) |
| `mergeHoldingUtxos`, merge delegations, featured-app rights | Roadmap |

## Reads

| TS SDK | Native |
|---|---|
| (scan proxy) DSO party | `ScanClient.dsoPartyId()` |
| ANS lookups | `ScanClient.lookupAnsEntryByName(_:)`, `listAnsEntries(pageSize:namePrefix:)` |
| `ValidatorController.getOpenMiningRounds` / `getAmuletRules` | Roadmap (traffic work) |
| `LedgerController.activeContracts`, `ledgerEnd` | `CantonKit`'s `CantonClient` (`activeContractsSnapshot`, `ledgerEnd`, offset-resumable `updates`) |

## Not covered here

- **CIP-0103 dApp connectivity** (`@canton-network/dapp-sdk`, Wallet Gateway):
  deliberately out of scope for 1.0 — see the README roadmap.
- **JSON Ledger API access**: not provided; the native SDKs speak gRPC. The
  generated service clients cover the full Ledger API surface if you need
  something the ergonomic layer doesn't wrap yet.

## Semantics worth knowing

- Interface-filtered reads request **verbose** values: non-verbose Ledger API
  values omit the record field labels typed decoders match on.
- Registry `choiceContextData` arrives in Daml JSON API encoding; this SDK
  re-encodes it to gRPC proto values internally (`AnyValue`'s closed
  constructor set makes that translation total) — you never handle it.
- Everything in the tables above marked with a concrete signature is covered
  by live integration tests against Canton and Splice LocalNet
  (`ExternalPartyIntegrationTest[s]`, `LocalNetTokenStandardIntegrationTest`).
