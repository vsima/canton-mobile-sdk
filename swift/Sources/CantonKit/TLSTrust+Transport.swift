// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import GRPCNIOTransportHTTP2

/// Maps ``TLSTrust`` onto whichever transport the platform selected. Both
/// transports express the same two things — the anchors and whether the
/// hostname must match — so only the enclosing type differs.
extension TLSTrust {

    private var certificateVerification: TLSConfig.CertificateVerification {
        verifyHostname ? .fullVerification : .noHostnameVerification
    }

    private var trustRootsSource: TLSConfig.TrustRootsSource {
        switch trustRoots {
        case .systemDefault:
            .systemDefault
        case .certificates(let certificates):
            .certificates(certificates.map { .bytes(Array($0), format: .der) })
        }
    }

    #if canImport(Network)
    func transportServicesTLS() -> HTTP2ClientTransport.TransportServices.TLS {
        var tls = HTTP2ClientTransport.TransportServices.TLS.defaults
        tls.trustRoots = trustRootsSource
        tls.serverCertificateVerification = certificateVerification
        return tls
    }
    #endif

    #if !canImport(Network)
    func posixTLS() -> HTTP2ClientTransport.Posix.TransportSecurity.TLS {
        var tls = HTTP2ClientTransport.Posix.TransportSecurity.TLS.defaults
        tls.trustRoots = trustRootsSource
        tls.serverCertificateVerification = certificateVerification
        return tls
    }
    #endif
}
