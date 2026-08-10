// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

#if canImport(Security)
import CantonKit
import Foundation
import Security

extension TLSTrust {
    /// A `URLSession` honouring the same trust anchors as the Ledger API
    /// connection, for the off-ledger REST APIs — ``ScanClient``,
    /// ``ValidatorClient`` and ``TransferRegistryClient``.
    ///
    /// Pinning the ledger connection while these keep the platform trust
    /// store is a quiet asymmetry: balances, fee schedules and registry
    /// choice contexts would still arrive over an interceptable
    /// connection. Configure trust once and pass the result to all three.
    ///
    /// ```swift
    /// let trust = TLSTrust(trustRoots: .certificates([operatorCA]))
    /// let scan = ScanClient(baseURL: scanURL, session: trust.urlSession())
    /// ```
    ///
    /// Returns a plain session when the trust is the platform default,
    /// which leaves ATS (including any `NSPinnedDomains` in the app's
    /// Info.plist) in charge.
    public func urlSession(
        configuration: URLSessionConfiguration = .default
    ) -> URLSession {
        guard let pinned = pinnedCertificates else {
            return URLSession(configuration: configuration)
        }
        let anchors = pinned.compactMap { SecCertificateCreateWithData(nil, $0 as CFData) }
        return URLSession(
            configuration: configuration,
            delegate: PinnedTrustDelegate(anchors: anchors, verifyHostname: verifyHostname),
            delegateQueue: nil
        )
    }
}

/// Evaluates the server's chain against the pinned anchors *only*, so a
/// certificate issued by any other authority — including one the device
/// trusts — is rejected.
private final class PinnedTrustDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    private let anchors: [SecCertificate]
    private let verifyHostname: Bool

    init(anchors: [SecCertificate], verifyHostname: Bool) {
        self.anchors = anchors
        self.verifyHostname = verifyHostname
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust
        else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        // Anchors-only: without this the system roots stay in play and the
        // pin would add nothing.
        guard SecTrustSetAnchorCertificates(trust, anchors as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess
        else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        if !verifyHostname {
            SecTrustSetPolicies(trust, SecPolicyCreateBasicX509())
        } else {
            let host = challenge.protectionSpace.host as CFString
            SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, host))
        }

        if SecTrustEvaluateWithError(trust, nil) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
#endif
