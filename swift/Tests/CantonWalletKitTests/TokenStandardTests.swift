import CantonKit
import CantonLedgerAPI
import Foundation
import Testing

@testable import CantonWalletKit

/// Twin of the Kotlin `TokenStandardDecodingTest` / `ChoiceContextJsonTest`:
/// identical view specs and the same shared fixture, so both SDKs must agree.
struct TokenStandardTests {

    private let requestedAt = Date(timeIntervalSince1970: 1_786_096_800)  // 2026-08-07T10:00:00Z
    private let executeBefore = Date(timeIntervalSince1970: 1_786_183_200)  // 2026-08-08T10:00:00Z

    @Test func decodesAnUnlockedHoldingView() throws {
        let view = Com_Daml_Ledger_Api_V2_Record.of([
            "owner": .party("alice::1220aa"),
            "instrumentId": .record(["admin": .party("dso::1220bb"), "id": .text("Amulet")]),
            "amount": .numeric("100.05"),
            "lock": .optional(),
            "meta": metadataValue(["k": "v"]),
        ])

        let holding = try Holding.fromView(contractId: "00cid", view: view)
        #expect(holding.owner == "alice::1220aa")
        #expect(holding.instrumentId == InstrumentId(admin: "dso::1220bb", id: "Amulet"))
        #expect(holding.amount == "100.05")
        #expect(holding.lock == nil)
        #expect(holding.meta == ["k": "v"])
    }

    @Test func decodesALockedHoldingWithExpiry() throws {
        let view = Com_Daml_Ledger_Api_V2_Record.of([
            "owner": .party("alice::1220aa"),
            "instrumentId": .record(["admin": .party("dso::1220bb"), "id": .text("Amulet")]),
            "amount": .numeric("7.0"),
            "lock": .optional(
                .record([
                    "holders": .list([.party("validator::1220cc")]),
                    "expiresAt": .optional(.timestamp(requestedAt)),
                    "expiresAfter": .optional(.record(["microseconds": .int64(60_000_000)])),
                    "context": .optional(.text("mining round")),
                ])
            ),
            "meta": metadataValue([:]),
        ])

        let lock = try #require(Holding.fromView(contractId: "00cid", view: view).lock)
        #expect(lock.holders == ["validator::1220cc"])
        #expect(lock.expiresAt == requestedAt)
        #expect(lock.expiresAfterMicros == 60_000_000)
        #expect(lock.context == "mining round")
    }

    @Test func decodesATransferInstructionPendingReceiverAcceptance() throws {
        let transfer = Transfer(
            sender: "alice::1220aa",
            receiver: "bob::1220dd",
            amount: "25.5",
            instrumentId: InstrumentId(admin: "dso::1220bb", id: "Amulet"),
            requestedAt: requestedAt,
            executeBefore: executeBefore,
            inputHoldingCids: ["00in1", "00in2"]
        )
        let view = Com_Daml_Ledger_Api_V2_Record.of([
            "originalInstructionCid": .optional(),
            "transfer": transfer.toValue(),
            "status": .variant(constructor: "TransferPendingReceiverAcceptance", value: .unit),
            "meta": metadataValue([:]),
        ])

        let instruction = try TransferInstruction.fromView(contractId: "00instr", view: view)
        #expect(instruction.originalInstructionCid == nil)
        #expect(instruction.status == .pendingReceiverAcceptance)
        // Encoding and decoding are inverses: the embedded transfer round-trips.
        #expect(instruction.transfer == transfer)
    }

    @Test func mapsEveryAnyValueConstructorInTheSharedFixture() throws {
        let fixtureURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // CantonWalletKitTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // swift
            .deletingLastPathComponent()  // repo root
            .appendingPathComponent("testdata/tokenstandard/choice-context.json")
        let fixture = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL))

        let context = try ChoiceContextJSON.choiceContextValue(fixture)
        var entries: [String: Com_Daml_Ledger_Api_V2_Value] = [:]
        for entry in try context.asRecord().requireField("values").textMapEntries() {
            entries[entry.key] = entry.value
        }

        #expect(entries.count == 12)

        func variant(_ key: String) throws -> Com_Daml_Ledger_Api_V2_Variant {
            try #require(entries[key]).asVariant()
        }

        #expect(try variant("amulet-rules").constructor == "AV_ContractId")
        #expect(
            try variant("amulet-rules").value.asContractId()
                == "00aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        )
        #expect(try variant("note").value.asText() == "token-standard choice context")
        #expect(try variant("count").value.asInt64() == 42)
        #expect(try variant("fee").value.asNumeric() == "1.5")
        #expect(try variant("featured").value.asBool() == true)
        #expect(try variant("expiry-date").value.asDate() == 20_672)  // 2026-08-07
        #expect(
            try variant("as-of").value.asTimestampMicroseconds() == 1_786_104_000_000_000
        )
        #expect(
            try variant("timeout").value.asRecord()
                .requireField("microseconds").asInt64() == 3_600_000_000
        )
        #expect(try variant("operator").value.asParty() == "operator::1220aabbcc")
        #expect(try variant("extra-cids").value.asList().count == 1)
        #expect(try variant("nested").value.textMapEntries().first?.key == "inner")
    }

    @Test func emptyAndAbsentContextsEncodeAsEmptyChoiceContext() throws {
        let empty = try ChoiceContextJSON.choiceContextValue(nil)
        #expect(try empty.asRecord().requireField("values").textMapEntries().isEmpty)
    }

    @Test func factoryChoiceArgumentsFollowDamlJsonEncoding() throws {
        let transfer = Transfer(
            sender: "alice::1220aa",
            receiver: "bob::1220dd",
            amount: "25.5",
            instrumentId: InstrumentId(admin: "dso::1220bb", id: "Amulet"),
            requestedAt: requestedAt,
            executeBefore: executeBefore,
            inputHoldingCids: ["00in1"],
            meta: ["reason": "invoice 7"]
        )

        let args = ChoiceContextJSON.transferFactoryChoiceArguments(
            expectedAdmin: "dso::1220bb",
            transfer: transfer
        )

        #expect(args["expectedAdmin"] as? String == "dso::1220bb")
        let transferJson = try #require(args["transfer"] as? [String: Any])
        #expect(transferJson["amount"] as? String == "25.5")
        #expect(transferJson["requestedAt"] as? String == "2026-08-07T10:00:00Z")
        let meta = try #require(transferJson["meta"] as? [String: Any])
        #expect((meta["values"] as? [String: String]) == ["reason": "invoice 7"])
        let extraArgs = try #require(args["extraArgs"] as? [String: Any])
        let context = try #require(extraArgs["context"] as? [String: Any])
        #expect((context["values"] as? [String: Any])?.isEmpty == true)
    }
}
