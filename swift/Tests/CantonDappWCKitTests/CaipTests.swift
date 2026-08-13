// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Testing

@testable import CantonDappWCKit

/// CAIP-2 / CAIP-10 round-trips — the Swift mirror of Kotlin `CaipTest`.
@Suite struct CaipTests {

    /// A CAIP-10 address must stay within `[-.%A-Za-z0-9]`.
    private func isCaip10Safe(_ c: Character) -> Bool {
        c == "-" || c == "." || c == "%"
            || ("A"..."Z").contains(c) || ("a"..."z").contains(c) || ("0"..."9").contains(c)
    }

    @Test func partyIdsRoundTripThroughACaip10Address() throws {
        let parties = [
            "preapproved::1220b3d98dd0362a19385d6878be4bafb2f12f13531ee7abcb8f32bdb2d764bac9be",
            "my_shop::1220abcDEF",
            "shopper::1220deadbeef",
        ]
        for party in parties {
            let address = Caip.encodeParty(party)
            #expect(!address.contains(":"), "a CAIP-10 address must not contain a colon: \(address)")
            #expect(address.allSatisfy(isCaip10Safe), "must stay within the CAIP-10 address charset: \(address)")
            let decoded = try Caip.decodeParty(address)
            #expect(decoded == party)
        }
    }

    @Test func underscoreAndColonArePercentEncoded() {
        #expect(Caip.encodeParty("my_shop::1220ab") == "my%5Fshop%3A%3A1220ab")
    }

    @Test func accountRoundTripsAgainstTheCaip2Chain() throws {
        let party = "preapproved::1220b3d98dd0362a19385d6878be4bafb2f12f13531ee7abcb8f32bdb2d764bac9be"
        let account = Caip.account(chainId: "canton:localnet", partyId: party)
        #expect(account.hasPrefix("canton:localnet:"))
        let recovered = try Caip.partyFromAccount(account)
        #expect(recovered == party)
    }

    @Test func chainIdValidatesACaip2NetworkId() throws {
        let ok = try Caip.chainId("canton:localnet")
        #expect(ok == "canton:localnet")
        #expect(throws: Caip.CaipError.self) { try Caip.chainId("not-a-chain") }
        #expect(throws: Caip.CaipError.self) { try Caip.chainId("canton:localnet:extra") }
    }
}
