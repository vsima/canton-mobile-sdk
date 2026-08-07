import CantonLedgerAPI
import Foundation

/// A registry call failed (non-2xx status or malformed payload).
public struct TransferRegistryError: Error, CustomStringConvertible {
    public let description: String
}

/// Client for a CIP-0056 registry's off-ledger transfer-instruction API
/// (`/registry/transfer-instruction/v1/...`).
///
/// Registries hand out two things a wallet cannot derive on its own: the
/// factory contract to exercise for a new transfer, and per-choice contexts
/// (referenced contracts + disclosed contracts) for accept/reject/withdraw.
public struct TransferRegistryClient: Sendable {
    public let baseURL: URL
    private let session: URLSession

    public init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// A contract the registry asks us to disclose with the command.
    public struct RegistryDisclosedContract: Sendable {
        public let templateId: String
        public let contractId: String
        public let createdEventBlobBase64: String
        public let synchronizerId: String

        /// As the Ledger API `DisclosedContract` for command submission.
        public func toProto() throws -> Com_Daml_Ledger_Api_V2_DisclosedContract {
            let segments = templateId.split(separator: ":", maxSplits: 2).map(String.init)
            guard segments.count == 3 else {
                throw TransferRegistryError(description: "malformed registry templateId: \(templateId)")
            }
            guard let blob = Data(base64Encoded: createdEventBlobBase64) else {
                throw TransferRegistryError(description: "malformed createdEventBlob for \(contractId)")
            }
            var proto = Com_Daml_Ledger_Api_V2_DisclosedContract()
            proto.templateID.packageID = segments[0]
            proto.templateID.moduleName = segments[1]
            proto.templateID.entityName = segments[2]
            proto.contractID = contractId
            proto.createdEventBlob = blob
            proto.synchronizerID = synchronizerId
            return proto
        }
    }

    public struct RegistryChoiceContext: @unchecked Sendable {
        /// Daml JSON encoding of the ChoiceContext, as parsed JSON (or nil).
        public let choiceContextData: Any?
        public let disclosedContracts: [RegistryDisclosedContract]
    }

    public struct TransferFactory: @unchecked Sendable {
        public let factoryId: String
        /// "self" | "direct" | "offer" — how the registry will route this transfer.
        public let transferKind: String
        public let choiceContext: RegistryChoiceContext
    }

    /// POST `/transfer-factory`: the factory + context for a new transfer.
    public func transferFactory(
        choiceArguments: [String: Any],
        excludeDebugFields: Bool = true
    ) async throws -> TransferFactory {
        let response = try await post(
            path: "registry/transfer-instruction/v1/transfer-factory",
            body: [
                "choiceArguments": choiceArguments,
                "excludeDebugFields": excludeDebugFields,
            ]
        )
        guard
            let factoryId = response["factoryId"] as? String,
            let transferKind = response["transferKind"] as? String
        else {
            throw TransferRegistryError(description: "registry response missing factoryId/transferKind")
        }
        return TransferFactory(
            factoryId: factoryId,
            transferKind: transferKind,
            choiceContext: try choiceContext(response["choiceContext"])
        )
    }

    /// POST `/{id}/choice-contexts/{accept|reject|withdraw}`.
    public func transferInstructionChoiceContext(
        transferInstructionId: String,
        choice: TransferInstructionChoice,
        meta: [String: String] = [:]
    ) async throws -> RegistryChoiceContext {
        var body: [String: Any] = ["excludeDebugFields": true]
        if !meta.isEmpty {
            body["meta"] = meta
        }
        let response = try await post(
            path: "registry/transfer-instruction/v1/\(transferInstructionId)"
                + "/choice-contexts/\(choice.registryPathSegment)",
            body: body
        )
        return try choiceContext(response)
    }

    private func choiceContext(_ json: Any?) throws -> RegistryChoiceContext {
        guard let object = json as? [String: Any] else {
            throw TransferRegistryError(description: "missing choiceContext in registry response")
        }
        let disclosed = try ((object["disclosedContracts"] as? [Any]) ?? []).map { entry in
            guard
                let contract = entry as? [String: Any],
                let templateId = contract["templateId"] as? String,
                let contractId = contract["contractId"] as? String,
                let blob = contract["createdEventBlob"] as? String,
                let synchronizerId = contract["synchronizerId"] as? String
            else {
                throw TransferRegistryError(description: "malformed disclosedContracts entry")
            }
            return RegistryDisclosedContract(
                templateId: templateId,
                contractId: contractId,
                createdEventBlobBase64: blob,
                synchronizerId: synchronizerId
            )
        }
        return RegistryChoiceContext(
            choiceContextData: object["choiceContextData"],
            disclosedContracts: disclosed
        )
    }

    private func post(path: String, body: [String: Any]) async throws -> [String: Any] {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let text = String(data: data, encoding: .utf8) ?? ""
            throw TransferRegistryError(description: "HTTP \(status) from \(path): \(text)")
        }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw TransferRegistryError(description: "registry response from \(path) is not a JSON object")
        }
        return object
    }
}
