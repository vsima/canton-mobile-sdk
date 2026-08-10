// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Which certificates to trust when connecting to a participant over TLS.
///
/// Mobile clients roam onto networks where TLS interception is a real
/// possibility — a corporate proxy or a debugging proxy whose CA the device
/// already trusts will otherwise be accepted like any other. Pinning the
/// trust anchors to the operator's own CA rejects those, because the
/// intercepting certificate does not chain to it.
///
/// The default is ``TrustRoots/systemDefault``: the platform trust store.
/// Note that Apple's ATS `NSPinnedDomains` covers `URLSession` only, so it
/// pins the SDK's REST calls but never the gRPC Ledger API connection —
/// this type is how that one gets pinned.
///
/// Pin **certificate authorities**, not leaves. Leaf certificates rotate
/// (every 90 days with Let's Encrypt) and a wallet talks to validators
/// whose rotation schedule it does not control, so a leaf pin is an outage
/// waiting for a renewal. This type deliberately offers no way to express
/// one — and on Apple platforms the default Network.framework transport
/// exposes no verification callback that could enforce it anyway.
///
/// ```swift
/// let operatorCA = try Data(contentsOf: caURL)   // DER
/// CantonClientConfiguration(
///     host: "validator.example.com",
///     tlsTrust: TLSTrust(trustRoots: .certificates([operatorCA]))
/// )
/// ```
public struct TLSTrust: Sendable, Equatable {

    public enum TrustRoots: Sendable, Equatable {
        /// The platform trust store.
        case systemDefault

        /// Trust only these certificates and what they sign. Each element
        /// is one DER-encoded X.509 certificate — normally an operator's CA.
        case certificates([Data])
    }

    /// The anchors a server certificate must chain to.
    public var trustRoots: TrustRoots

    /// Whether the certificate must also match the host being connected to.
    /// Leave this on: turning it off accepts any certificate the pinned CA
    /// ever issued, for any name. It exists for self-signed deployment
    /// certificates issued without a matching SAN.
    public var verifyHostname: Bool

    public init(trustRoots: TrustRoots = .systemDefault, verifyHostname: Bool = true) {
        self.trustRoots = trustRoots
        self.verifyHostname = verifyHostname
    }

    /// The pinned certificates as DER, or nil when using the platform store.
    public var pinnedCertificates: [Data]? {
        switch trustRoots {
        case .systemDefault: nil
        case .certificates(let certificates): certificates
        }
    }
}
