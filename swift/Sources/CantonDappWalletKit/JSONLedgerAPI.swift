// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A minimal client for Canton's **JSON** Ledger API, used by the dApp
/// provider for the two things that must go over JSON rather than gRPC:
/// `interactive-submission/prepare` (see ``JSONPrepareExecutePipeline``) and
/// the `ledgerApi` proxy method.
///
/// Why JSON at all, in an SDK whose point is the gRPC Ledger API: a dApp
/// authors its commands as **JSON Ledger API** shapes, and the participant
/// must see them exactly as authored. Re-encoding them into proto would add a
/// drift surface for every Daml value shape — the risk hash verification
/// exists to catch. So the commands travel as written, and only the prepared
/// *result* crosses back into proto, which it can because it crosses as bytes.
///
/// ### Field requirements are stricter than the published schema
///
/// Canton 3.5.12 serves its own OpenAPI at `/docs/openapi`, and **that
/// document under-reports required fields.** Verified live: `prepare`
/// documents `commandId`, `commands` and `actAs` as required, then rejects a
/// request carrying exactly those three because `synchronizerId` and
/// `packageIdSelectionPreference` are missing too. Do not generate a client
/// from it; send every field, empty collections included.
public struct JSONLedgerAPIClient: Sendable {
    private let baseURL: String
    private let accessTokenProvider: (@Sendable () async throws -> String)?
    private let session: URLSession

    /// - Parameter session: pass ``TLSTrust/urlSession(configuration:)`` to
    ///   pin this connection to the same anchors as the ledger channel.
    ///   Leaving it `.shared` means the JSON API is trusted by the platform
    ///   store while the gRPC channel may be pinned — a quiet asymmetry worth
    ///   closing deliberately rather than by default.
    public init(
        baseURL: String,
        accessTokenProvider: (@Sendable () async throws -> String)? = nil,
        session: URLSession = .shared
    ) {
        self.baseURL = baseURL
        self.accessTokenProvider = accessTokenProvider
        self.session = session
    }

    /// POSTs `body` to `path` and returns the decoded response.
    public func post(_ path: String, body: JSONValue) async throws -> JSONValue {
        var request = URLRequest(url: try resolve(path, query: nil))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.httpBody = try body.serialized()
        return try await execute(request)
    }

    /// Performs a dApp's `ledgerApi` request with the wallet's credentials.
    public func call(_ apiRequest: LedgerApiRequest) async throws -> JSONValue {
        var request = URLRequest(url: try resolve(apiRequest.resource, query: apiRequest.query))
        request.httpMethod = apiRequest.requestMethod.rawValue.uppercased()
        if let body = apiRequest.body, !body.isNull {
            request.setValue("application/json", forHTTPHeaderField: "content-type")
            request.httpBody = try body.serialized()
        }
        return try await execute(request)
    }

    private func resolve(_ resource: String, query: [String: JSONValue]?) throws -> URL {
        let base = baseURL.hasSuffix("/") ? String(baseURL.dropLast()) : baseURL
        let path = resource.hasPrefix("/") ? resource : "/\(resource)"
        guard var components = URLComponents(string: base + path) else {
            throw DappError(code: .invalidParams, message: "not a valid ledger API URL: \(base)\(path)")
        }
        if let query, !query.isEmpty {
            components.queryItems = query.map { key, value in
                URLQueryItem(name: key, value: value.stringValue ?? String(describing: value))
            }
        }
        guard let url = components.url else {
            throw DappError(code: .invalidParams, message: "could not build a URL for \(path)")
        }
        return url
    }

    private func execute(_ request: URLRequest) async throws -> JSONValue {
        var authorized = request
        if let accessTokenProvider {
            // Minted per call rather than captured once: a token cached for
            // the life of a session outlives its own expiry.
            let token = try await accessTokenProvider()
            authorized.setValue("Bearer \(token)", forHTTPHeaderField: "authorization")
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: authorized)
        } catch {
            throw DappError(
                code: .internalError,
                message: "could not reach the JSON Ledger API: \(error.localizedDescription)"
            )
        }

        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw Self.mapError(status: status, data: data)
        }
        guard !data.isEmpty else { return .null }
        return try JSONValue.parse(data)
    }

    /// Canton error → CIP-0103 error.
    ///
    /// Two response shapes, and telling them apart is most of the debugging
    /// value: a bare `Invalid value for: body (...)` came from the JSON
    /// decoding layer and means *our* envelope was malformed, never the dApp's
    /// commands. Anything carrying `code` and `traceId` reached the ledger.
    ///
    /// The mapping key is `grpcCodeValue`, not `errorCategory` — the latter is
    /// `-1` on security-redacted errors, which is exactly when you most need
    /// to know what happened.
    static func mapError(status: Int, data: Data) -> DappError {
        let text = String(data: data, encoding: .utf8) ?? ""
        guard let body = (try? JSONValue.parse(data))?.objectValue else {
            return DappError(
                code: .invalidParams,
                message: "ledger API rejected the request (HTTP \(status)): \(text.prefix(500))"
            )
        }
        let grpcCode = body["grpcCodeValue"]?.int64Value
        let code: DappErrorCode
        switch grpcCode {
        case 3: code = .invalidParams // INVALID_ARGUMENT
        case 5: code = .invalidInput // NOT_FOUND
        case 7, 16: code = .unauthorized // PERMISSION_DENIED, UNAUTHENTICATED
        case 9: code = .transactionRejected // FAILED_PRECONDITION
        case 14: code = .disconnected // UNAVAILABLE
        default: code = (400..<500).contains(status) ? .invalidInput : .internalError
        }
        let message = [body["code"]?.stringValue, body["cause"]?.stringValue]
            .compactMap { $0 }
            .joined(separator: ": ")
        return DappError(
            code: code,
            message: message.isEmpty ? "ledger API error (HTTP \(status))" : message,
            // Carries traceId/correlationId through untouched — a wallet
            // should not have to model Canton's error envelope to let a dApp
            // quote it in a support request.
            data: .object(body)
        )
    }
}

/// `ledgerApi` backed by ``JSONLedgerAPIClient``.
///
/// The wallet supplies the credentials, so this is the authenticating proxy
/// CIP-0103 describes. What it must *not* become is an open door: pair it with
/// a ``LedgerApiPolicy`` — ``DappSession`` applies ``LedgerApiPolicy/readOnly``
/// by default — because the wallet's token is considerably more privileged
/// than anything a dApp should wield through it.
public struct HTTPLedgerApiProxy: LedgerApiProxy {
    private let client: JSONLedgerAPIClient

    public init(client: JSONLedgerAPIClient) {
        self.client = client
    }

    public func call(_ request: LedgerApiRequest) async throws -> JSONValue {
        try await client.call(request)
    }
}
