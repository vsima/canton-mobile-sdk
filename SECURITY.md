# Security

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository:
**Security → Report a vulnerability** (or
https://github.com/vsima/canton-mobile-sdk/security/advisories/new). It
reaches the maintainer privately; please do not open a public issue for a
security problem. Expect an acknowledgement within three working days and a
fix or a mitigation plan within thirty for confirmed issues in supported
versions.

## Supported versions

Pre-1.0: only the latest minor release line receives fixes.

| Version | Supported |
|---|---|
| 0.6.x | yes |
| < 0.6 | no |

## What is in scope

- The signing drivers (`SecureEnclaveSigningDriver`, the Android Keystore
  driver with StrongBox and TEE tiers, `SoftwareSigningDriver`) and the
  guarantees around them: keys created in hardware are never exportable, and
  the achieved tier is reported truthfully.
- Prepared-transaction hash verification (`signAndExecute` verifies by
  default; the shared golden vectors in `testdata/` define the hash).
- The CIP-0103 wallet-side engine: grants, `actAs` authorization, the
  spend policy and receipts, and the WalletConnect transport adapter
  (including its exactly-once handling of redelivered requests).
- TLS trust configuration for the Ledger API connection.

Out of scope: the Canton participant, the Splice registry, the WalletConnect
relay, and the reference apps in `canton-mobile-app` (report those there).

## Audit status

No third-party audit has been performed yet. A scoped audit of the signing
drivers, hash verification, and the WalletConnect transport is planned;
this file will name the auditor and link the report when it exists. Until
then, the verification you can check yourself is the golden-vector suites
and the live integration runs described in the README.
