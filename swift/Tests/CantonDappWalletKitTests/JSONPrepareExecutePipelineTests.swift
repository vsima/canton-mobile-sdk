// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import CantonLedgerAPI
import Foundation
import Testing

@testable import CantonDappWalletKit

/// The two halves of the pipeline that carry the design's weight: the envelope
/// the wallet builds around the dApp's commands, and the decode that brings
/// the prepared transaction back from JSON into protobuf. Mirrors
/// `JsonPrepareExecutePipelineTest.kt`.
///
/// The signing and submission half is deliberately not re-tested — it is
/// `InteractiveSubmissionClient.signAndExecuteAndWait`, which already has
/// unit, golden-vector and live coverage. Re-asserting it would test the mock.
@Suite struct JSONPrepareExecutePipelineTests {

    let alice = DappWallet(
        primary: true,
        partyId: "alice::1220aa",
        status: .allocated,
        hint: "alice",
        publicKey: "00",
        namespace: "1220aa",
        networkId: "canton:localnet",
        signingProviderId: "software"
    )

    let commands: [JSONValue] = [
        .object(["CreateCommand": .object(["templateId": .string("pkg:M:T")])])
    ]

    let network = DappNetworkConfig(
        networkId: "canton:localnet",
        jsonApiBaseUrl: "http://127.0.0.1:2975",
        synchronizerId: "global-domain::1220bb"
    )

    func context(_ submission: PrepareSubmission) -> PrepareExecuteContext {
        PrepareExecuteContext(
            commandId: "order-4711",
            actAs: alice,
            submission: submission,
            network: network
        )
    }

    func envelope(_ submission: PrepareSubmission, userId: String? = nil) -> [String: JSONValue] {
        JSONPrepareExecutePipeline.buildPrepareRequest(
            context: context(submission),
            synchronizerId: network.synchronizerId!,
            userId: userId
        ).objectValue!
    }

    // ── The envelope ───────────────────────────────────────────────────

    @Test func theEnvelopeCarriesEveryFieldCantonsDecoderDemands() {
        let request = envelope(PrepareSubmission(commands: commands))

        // Canton's own OpenAPI lists only commandId, commands and actAs as
        // required, then rejects a body carrying exactly those three. All five
        // go every time — verified live against 3.5.12.
        for field in [
            "commandId", "commands", "actAs", "synchronizerId", "packageIdSelectionPreference",
        ] {
            #expect(request[field] != nil, "envelope is missing '\(field)'")
        }
    }

    @Test func theDappsCommandsPassThroughUntouched() {
        let request = envelope(PrepareSubmission(commands: commands))

        // Byte-for-byte the same JSON the dApp authored: re-encoding is the
        // drift surface the whole proxy design exists to avoid.
        #expect(request["commands"] == .array(commands))
    }

    @Test func actAsIsTheApprovedAccountNotWhateverTheDappAskedFor() {
        // A dApp naming someone else entirely. DappSession would already have
        // rejected this; the pipeline must not honour it either.
        let request = envelope(
            PrepareSubmission(commands: commands, actAs: ["mallory::1220cc"])
        )

        #expect(request["actAs"] == .array([.string(alice.partyId)]))
    }

    @Test func theSynchronizerIsTheWalletsNeverTheDapps() {
        let request = envelope(
            PrepareSubmission(commands: commands, synchronizerId: "attacker-domain::dead")
        )

        #expect(request["synchronizerId"] == .string("global-domain::1220bb"))
    }

    @Test func readAsAndDisclosedContractsPassThroughWhenSupplied() {
        let disclosed: [JSONValue] = [.object(["contractId": .string("00feed")])]
        let request = envelope(
            PrepareSubmission(
                commands: commands,
                readAs: ["bob::1220bb"],
                disclosedContracts: disclosed
            )
        )

        // Neither widens who acts, so the dApp may influence both.
        #expect(request["readAs"] == .array([.string("bob::1220bb")]))
        #expect(request["disclosedContracts"] == .array(disclosed))
    }

    @Test func readAsIsOmittedRatherThanSentEmpty() {
        let request = envelope(PrepareSubmission(commands: commands))

        #expect(request["readAs"] == nil)
        #expect(request["userId"] == nil)
    }

    @Test func userIdRidesAlongWhenTheParticipantScopesByUser() {
        let request = envelope(PrepareSubmission(commands: commands), userId: "app-user")

        #expect(request["userId"] == .string("app-user"))
    }

    // ── The decode ─────────────────────────────────────────────────────

    func preparedResponse(
        _ transaction: Com_Daml_Ledger_Api_V2_Interactive_PreparedTransaction,
        hash: Data = Data([1, 2, 3]),
        scheme: String? = "HASHING_SCHEME_VERSION_V2"
    ) throws -> JSONValue {
        var object: [String: JSONValue] = [
            "preparedTransaction": .string(try transaction.serializedData().base64EncodedString()),
            "preparedTransactionHash": .string(hash.base64EncodedString()),
        ]
        if let scheme { object["hashingSchemeVersion"] = .string(scheme) }
        return .object(object)
    }

    @Test func aBase64PreparedTransactionDecodesBackIntoTheExactProto() throws {
        // The linchpin: JSON hands back base64 of the serialized protobuf, so
        // the bytes reach the hash verifier without a transcode. If Canton
        // ever changes this field to a structured object, this test is what
        // fails.
        var original = Com_Daml_Ledger_Api_V2_Interactive_PreparedTransaction()
        original.metadata.submitterInfo.actAs = ["alice::1220aa"]

        let decoded = try JSONPrepareExecutePipeline.decodePrepared(
            try preparedResponse(original)
        )

        #expect(decoded.preparedTransaction == original)
        #expect(decoded.preparedTransactionHash == Data([1, 2, 3]))
    }

    @Test func theHashingSchemeIsCarriedThrough() throws {
        let decoded = try JSONPrepareExecutePipeline.decodePrepared(
            try preparedResponse(.init(), scheme: "HASHING_SCHEME_VERSION_V3")
        )

        #expect(decoded.hashingSchemeVersion == .v3)
    }

    @Test func anAbsentHashingSchemeDefaultsToV2() throws {
        let decoded = try JSONPrepareExecutePipeline.decodePrepared(
            try preparedResponse(.init(), scheme: nil)
        )

        // What Canton documents as its default; guessing `.unspecified` would
        // make execute fail with a far less obvious error.
        #expect(decoded.hashingSchemeVersion == .v2)
    }

    @Test func aNonBase64PreparedTransactionFailsLegibly() throws {
        var thrown: DappError?
        do {
            _ = try JSONPrepareExecutePipeline.decodePrepared(
                .object([
                    "preparedTransaction": .string("!!! not base64 !!!"),
                    "preparedTransactionHash": .string("AQID"),
                ])
            )
        } catch let error as DappError {
            thrown = error
        }

        #expect(thrown?.code == .internalError)
        #expect(thrown?.message.contains("base64") == true)
    }

    @Test func aMissingPreparedTransactionFailsLegibly() throws {
        var thrown: DappError?
        do {
            _ = try JSONPrepareExecutePipeline.decodePrepared(
                .object(["preparedTransactionHash": .string("AQID")])
            )
        } catch let error as DappError {
            thrown = error
        }

        #expect(thrown?.code == .internalError)
        #expect(thrown?.message.contains("preparedTransaction") == true)
    }

    // ── Error mapping ──────────────────────────────────────────────────

    @Test func theJSONDecodingLayerMapsOntoInvalidParams() {
        // A bare string body, no JSON: the shape Canton returns when the
        // envelope itself failed to decode. It means *our* request was
        // malformed, never the dApp's commands.
        let error = JSONLedgerAPIClient.mapError(
            status: 400,
            data: Data("Invalid value for: body (Missing required field at 'synchronizerId')".utf8)
        )

        #expect(error.code == .invalidParams)
        #expect(error.message.contains("synchronizerId"))
    }

    @Test func aParticipantErrorMapsByGrpcCodeValueAndKeepsItsTraceId() {
        let error = JSONLedgerAPIClient.mapError(
            status: 400,
            data: Data(
                #"{"code":"MISSING_FIELD","cause":"missing a mandatory field: commands","traceId":"edb2e49d","grpcCodeValue":3}"#
                    .utf8
            )
        )

        #expect(error.code == .invalidParams)
        #expect(error.message.contains("MISSING_FIELD"))
        // The traceId is what a user quotes in a support request; losing it to
        // make the error "clean" would be a bad trade.
        #expect(error.data?.objectValue?["traceId"] == .string("edb2e49d"))
    }

    @Test func aRedactedPermissionErrorMapsToUnauthorized() {
        // Exactly what LocalNet returns for `app-user` reading /v2/parties:
        // grpcCodeValue 7, errorCategory -1, and no useful message.
        let error = JSONLedgerAPIClient.mapError(
            status: 403,
            data: Data(
                #"{"code":"NA","cause":"A security-sensitive error has been received","errorCategory":-1,"grpcCodeValue":7}"#
                    .utf8
            )
        )

        #expect(error.code == .unauthorized)
    }

    @Test func anUnreachableLedgerIsAnInternalErrorNotAProtocolOne() async throws {
        // Port 1 is reliably closed. A dApp must be able to tell "the wallet
        // said no" from "the wallet could not reach the ledger".
        let client = JSONLedgerAPIClient(baseURL: "http://127.0.0.1:1")

        var thrown: DappError?
        do {
            _ = try await client.call(LedgerApiRequest(requestMethod: .get, resource: "/v2/version"))
        } catch let error as DappError {
            thrown = error
        }

        #expect(thrown?.code == .internalError)
    }
}
