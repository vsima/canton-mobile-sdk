// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaipTest {

    @Test
    fun `party ids round-trip through a CAIP-10 address`() {
        val parties = listOf(
            "preapproved::1220b3d98dd0362a19385d6878be4bafb2f12f13531ee7abcb8f32bdb2d764bac9be",
            "my_shop::1220abcDEF",
            "shopper::1220deadbeef",
        )
        val charset = Regex("^[-.%A-Za-z0-9]+$")
        for (party in parties) {
            val address = Caip.encodeParty(party)
            assertFalse(address.contains(':'), "a CAIP-10 address must not contain a colon: $address")
            assertTrue(charset.matches(address), "must stay within the CAIP-10 address charset: $address")
            assertEquals(party, Caip.decodeParty(address))
        }
    }

    @Test
    fun `underscore and colon are percent-encoded`() {
        assertEquals("my%5Fshop%3A%3A1220ab", Caip.encodeParty("my_shop::1220ab"))
    }

    @Test
    fun `account round-trips against the CAIP-2 chain`() {
        val party = "preapproved::1220b3d98dd0362a19385d6878be4bafb2f12f13531ee7abcb8f32bdb2d764bac9be"
        val account = Caip.account("canton:localnet", party)
        assertTrue(account.startsWith("canton:localnet:"))
        assertEquals(party, Caip.partyFromAccount(account))
    }

    @Test
    fun `chainId validates a CAIP-2 network id`() {
        assertEquals("canton:localnet", Caip.chainId("canton:localnet"))
        assertFailsWith<IllegalArgumentException> { Caip.chainId("not-a-chain") }
        assertFailsWith<IllegalArgumentException> { Caip.chainId("canton:localnet:extra") }
    }
}
