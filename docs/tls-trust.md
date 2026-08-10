# TLS trust and certificate pinning

A wallet runs on devices that roam onto networks you don't control. The
attack this page is about is TLS interception by a certificate authority
the device already trusts — a corporate proxy, a captive portal appliance,
or a debugging proxy someone installed once and forgot. Against those, the
platform trust store is working exactly as designed, and that is the
problem.

Pinning the trust anchors to the operator's own CA rejects them, because
the intercepting certificate does not chain to it.

## You may not need the SDK for this

Both SDKs let you supply the HTTP client used for the off-ledger REST APIs
(`ScanClient`, `ValidatorClient`, `TransferRegistryClient`), and both
platforms ship declarative pinning that already covers those. What neither
platform covers is the gRPC Ledger API connection, which the SDK opens
itself — that is the gap `TlsTrust` fills.

| Surface | Android | JVM (server, desktop) | Apple |
|---|---|---|---|
| Ledger API (gRPC) | Network Security Config, or `TlsTrust` | `TlsTrust` | `TLSTrust` — ATS does not apply to gRPC |
| Scan / validator / registry (REST) | Network Security Config, or `TlsTrust.okHttpClient()` | `TlsTrust.okHttpClient()` | ATS `NSPinnedDomains`, or `TLSTrust.urlSession()` |

On **Android**, `network_security_config.xml` is usually the better tool:
it covers every connection the app makes, including ones the SDK knows
nothing about, and it is reviewable in the manifest rather than buried in
code. Reach for `TlsTrust` when the anchors aren't known at build time.

On **Apple platforms**, ATS `NSPinnedDomains` (iOS 14+) pins `URLSession`
traffic — which is the SDK's REST calls — but has no effect on the
NIO-based gRPC connection. Pinning both means ATS *and* `TLSTrust`, or
`TLSTrust` for both via `urlSession()`.

## Usage

```kotlin
val operatorCa = assets.open("operator-ca.der").readBytes()
val trust = TlsTrust(TlsTrust.TrustRoots.Certificates(listOf(operatorCa)))

val client = CantonClient(
    CantonClientConfiguration(host = "validator.example.com", tlsTrust = trust)
)
// Same anchors for the off-ledger APIs, or they stay on system trust:
val scan = ScanClient(scanUrl, trust.okHttpClient())
```

```swift
let operatorCA = try Data(contentsOf: caURL)   // DER
let trust = TLSTrust(trustRoots: .certificates([operatorCA]))

let client = CantonClient(
    configuration: .init(host: "validator.example.com", tlsTrust: trust)
)
let scan = ScanClient(baseURL: scanURL, session: trust.urlSession())
```

Certificates are **DER**, one X.509 certificate per entry. Apple's
`SecCertificateCreateWithData` requires DER, so accepting it directly
avoids a conversion step that would only ever fail at runtime.

## Pin authorities, not leaves

There is deliberately no way to pin a leaf certificate or its public key
(SPKI). Two reasons, and the second one is the one that bites:

1. **It wouldn't work everywhere.** On Apple platforms the default
   transport is Network.framework, which exposes no custom verification
   callback — leaf pinning is not expressible there at all.
2. **It breaks wallets.** Leaves rotate, often every 90 days. A wallet
   talks to validators run by other people on schedules it does not
   control, so a leaf pin is an outage waiting for someone else's renewal.

Pinning the issuing CA defeats the interception threat completely while
surviving rotation. If you have a case that genuinely needs leaf pinning
on Kotlin, `CantonClient(channel, …)` accepts a fully configured
`ManagedChannel` and you can do anything gRPC supports.

## Operational notes

- **Never ship a default pin in a library.** This SDK doesn't. Wallets
  connect to operator-run validators, and a bundled anchor would brick the
  app the day someone else rotates their CA.
- **Have a recovery path.** If you pin, a validator changing CA makes your
  app unable to connect until it is updated. Ship anchors you can update
  (remote config, multiple anchors during a migration) rather than a
  single one baked into a release.
- **`verifyHostname` should stay on.** Turning it off means accepting any
  certificate your pinned CA ever issued, for any name. It exists for
  self-signed deployment certificates issued without a matching SAN.
- Pinning protects the transport. It is not a substitute for the
  prepared-transaction hash verification that protects *what gets signed*
  — see [prepared-tx-hash.md](prepared-tx-hash.md).

## Test fixtures

`testdata/tls/` holds a CA, a server leaf it signed, and a second
unrelated CA, driving the trust tests in both SDKs. Regenerate with
`tools/gen-test-certs.sh`.

The CAs are dated a century out; the **leaf is 397 days** because Apple
rejects TLS server certificates valid for longer than 398 days. It
therefore needs periodic regeneration — both suites fail with that
instruction rather than a bare handshake error when it lapses.
