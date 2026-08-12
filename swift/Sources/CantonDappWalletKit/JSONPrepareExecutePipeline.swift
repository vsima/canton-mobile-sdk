// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import CantonLedgerAPI
import CantonWalletKit
import Foundation

/// The `prepareExecute` pipeline: JSON prepare → verify → sign → gRPC execute.
///
/// ### Why the transaction can cross between JSON and gRPC unharmed
///
/// In the gRPC API `PrepareSubmissionResponse.prepared_transaction` is a
/// *message*; in the JSON API the same field is a *string*. Verified live
/// against Canton 3.5.12: that string is **base64 of the serialized protobuf**
/// — the server rejects non-base64 with `The string is not a valid Base64` and
/// rejects a JSON object with `wrong type, expecting string`. The rule
/// generalises: every proto `bytes` field in that API is base64, which is why
/// `preparedTransactionHash` decodes the same way.
///
/// That fact is what makes this design sound. The prepared transaction is
/// decoded straight back into protobuf and handed to the **existing,
/// golden-vector-tested** verifier and submission path; there is no JSON→proto
/// transcode anywhere, so nothing can drift between what the participant
/// prepared, what the user approved, and what gets signed.
///
/// ### What the wallet supplies, and why
///
/// The dApp's `commands` are proxied verbatim. Everything around them is the
/// wallet's, and `actAs` above all: it is the party the *user approved*, taken
/// from ``PrepareExecuteContext/actAs``, never from the dApp's request. A dApp
/// able to set it could make the wallet act as any party it names.
/// ``DappSession`` has already validated any requested `actAs` against the
/// peer's grant before this runs.
public struct JSONPrepareExecutePipeline: PrepareExecutePipeline {
    private let ledgerAPI: JSONLedgerAPIClient
    private let submission: InteractiveSubmissionClient
    private let signer: any SigningDriver
    private let keyFingerprint: @Sendable (DappWallet) -> String
    private let userId: String?

    /// - Parameter keyFingerprint: the fingerprint of the party's registered
    ///   signing key, carried as the signature's `signed_by`. Defaults to the
    ///   party id's namespace — correct for external parties whose namespace
    ///   *is* their signing key, which is how `ExternalPartyClient` onboards
    ///   them. Override for any other topology.
    public init(
        ledgerAPI: JSONLedgerAPIClient,
        submission: InteractiveSubmissionClient,
        signer: any SigningDriver,
        keyFingerprint: @escaping @Sendable (DappWallet) -> String = { $0.namespace },
        userId: String? = nil
    ) {
        self.ledgerAPI = ledgerAPI
        self.submission = submission
        self.signer = signer
        self.keyFingerprint = keyFingerprint
        self.userId = userId
    }

    public func execute(_ context: PrepareExecuteContext) async throws -> TxChangedEvent {
        guard let synchronizerId = context.network.synchronizerId else {
            throw DappError(
                code: .internalError,
                message: "DappNetworkConfig.synchronizerId is not set; the wallet must choose "
                    + "the synchronizer, so this cannot be defaulted from the dApp's request"
            )
        }

        let request = Self.buildPrepareRequest(
            context: context,
            synchronizerId: synchronizerId,
            userId: userId
        )
        let prepared = try Self.decodePrepared(
            try await ledgerAPI.post(Self.preparePath, body: request)
        )

        // signAndExecuteAndWait recomputes the hash from the prepared bytes
        // and refuses to sign on mismatch, then awaits the ledger completion.
        // Reused rather than reimplemented: it is the path the golden vectors
        // and the live suites already hold to.
        let completion: SubmissionCompletion
        do {
            completion = try await submission.signAndExecuteAndWait(
                prepared: prepared,
                driver: signer,
                partyId: context.actAs.partyId,
                keyFingerprint: keyFingerprint(context.actAs),
                userId: userId
            )
        } catch let error as PreparedTransactionHashMismatchError {
            // The participant's prepared bytes do not hash to the hash it
            // reported. Nothing was signed. This check exists for exactly one
            // reason, so say so plainly rather than as -32603.
            throw DappError(
                code: .transactionRejected,
                message: "prepared transaction failed hash verification; refusing to sign "
                    + "(\(error.description))"
            )
        }

        return .executed(
            commandId: context.commandId,
            updateId: completion.updateId,
            completionOffset: completion.offset
        )
    }

    // ── The envelope ───────────────────────────────────────────────────

    /// The prepare envelope: the dApp's `commands`, everything else ours.
    ///
    /// Carries fields Canton's published OpenAPI calls optional —
    /// `synchronizerId` and `packageIdSelectionPreference`. The decoder
    /// requires them anyway (verified live), so they are always sent.
    ///
    /// `static` and non-private so the tests can assert on it directly: the
    /// property that matters — `actAs` comes from the approved account, not
    /// the request — is not observable through a mocked HTTP round trip
    /// without also standing up a gRPC channel.
    static func buildPrepareRequest(
        context: PrepareExecuteContext,
        synchronizerId: String,
        userId: String?
    ) -> JSONValue {
        var object: [String: JSONValue] = [
            "commandId": .string(context.commandId),
            // The dApp's payload, untouched.
            "commands": .array(context.submission.commands),
            "actAs": .array([.string(context.actAs.partyId)]),
            "synchronizerId": .string(synchronizerId),
            "packageIdSelectionPreference":
                .array(context.submission.packageIdSelectionPreference.map { .string($0) }),
        ]
        // Pass-throughs: harmless for the dApp to influence, since neither
        // widens who acts.
        if !context.submission.readAs.isEmpty {
            object["readAs"] = .array(context.submission.readAs.map { .string($0) })
        }
        if let disclosed = context.submission.disclosedContracts {
            object["disclosedContracts"] = .array(disclosed)
        }
        if let userId {
            object["userId"] = .string(userId)
        }
        return .object(object)
    }

    // ── The decode ─────────────────────────────────────────────────────

    static func decodePrepared(
        _ response: JSONValue
    ) throws -> Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse {
        guard let object = response.objectValue else {
            throw DappError(code: .internalError, message: "prepare did not return an object")
        }
        let transactionBytes = try base64(object, "preparedTransaction")
        let hashBytes = try base64(object, "preparedTransactionHash")

        let transaction: Com_Daml_Ledger_Api_V2_Interactive_PreparedTransaction
        do {
            transaction = try Com_Daml_Ledger_Api_V2_Interactive_PreparedTransaction(
                serializedBytes: transactionBytes
            )
        } catch {
            throw DappError(
                code: .internalError,
                message: "prepare returned a preparedTransaction that is not a PreparedTransaction "
                    + "proto (\(transactionBytes.count) bytes). If Canton has changed this field "
                    + "from base64 protobuf to a structured object, this pipeline needs revisiting."
            )
        }

        var prepared = Com_Daml_Ledger_Api_V2_Interactive_PrepareSubmissionResponse()
        prepared.preparedTransaction = transaction
        prepared.preparedTransactionHash = hashBytes
        // Canton documents V2 as the default; guessing `.unspecified` would
        // make execute fail with a far less obvious error.
        prepared.hashingSchemeVersion = schemeVersion(object["hashingSchemeVersion"]?.stringValue)
        return prepared
    }

    private static func schemeVersion(
        _ name: String?
    ) -> Com_Daml_Ledger_Api_V2_Interactive_HashingSchemeVersion {
        switch name {
        case "HASHING_SCHEME_VERSION_V3": return .v3
        case "HASHING_SCHEME_VERSION_UNSPECIFIED": return .unspecified
        default: return .v2
        }
    }

    private static func base64(_ object: [String: JSONValue], _ field: String) throws -> Data {
        guard let text = object[field]?.stringValue else {
            throw DappError(code: .internalError, message: "prepare response is missing '\(field)'")
        }
        guard let data = Data(base64Encoded: text) else {
            throw DappError(
                code: .internalError,
                message: "prepare response field '\(field)' is not base64"
            )
        }
        return data
    }

    static let preparePath = "/v2/interactive-submission/prepare"
}

/// `signMessage` backed by a `SigningDriver`.
///
/// The signature is over ``DappSignMessage/signingBytes(_:)`` — the message
/// behind a fixed domain-separation prefix — not the raw message, so a
/// `signMessage` signature can never be mistaken for a signature over a
/// transaction hash the same key also produces. Returned base64-encoded in the
/// driver's native format (DER for ECDSA, raw for Ed25519); a dApp verifying it
/// must reconstruct the same signing bytes.
///
/// ``DappSession`` additionally gates every call behind user approval and a
/// rate limit.
public struct SigningDriverMessageSigner: DappMessageSigner {
    private let signer: any SigningDriver

    public init(signer: any SigningDriver) {
        self.signer = signer
    }

    public func sign(account: DappWallet, message: String) async throws -> String {
        // Domain-separated, not the raw message — see DappSignMessage. A dApp
        // verifying this signature must apply DappSignMessage.signingBytes too.
        let signature = try await signer.sign(DappSignMessage.signingBytes(message))
        return signature.signature.base64EncodedString()
    }
}
