// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The policy's pure decision table. The rules that carry weight: hard caps
 * refuse rather than ask, unparsed submissions are never auto-approved and
 * never refused by amount, and auto-approval is bounded by every other limit.
 */
class DappSpendPolicyTest {

    private fun transfer(
        amount: String = "2",
        instrument: String = "Amulet",
        receiver: String = "bob::1220bb",
    ) = DappTransferSummary(receiver = receiver, amount = amount, instrumentId = instrument)

    private val zero = BigDecimal.ZERO

    @Test
    fun `an empty policy asks the human for everything`() {
        val policy = DappSpendPolicy()
        assertEquals(SpendDecision.AskHuman, policy.decide(transfer(), zero))
        assertEquals(SpendDecision.AskHuman, policy.decide(transfer(amount = "999999"), zero))
        assertEquals(SpendDecision.AskHuman, policy.decide(null, zero))
    }

    @Test
    fun `autoApproveBelow approves at or under the bound and asks above it`() {
        val policy = DappSpendPolicy(autoApproveBelow = BigDecimal("5"))
        assertEquals(SpendDecision.AutoApprove, policy.decide(transfer(amount = "2"), zero))
        assertEquals(SpendDecision.AutoApprove, policy.decide(transfer(amount = "5"), zero))
        assertEquals(SpendDecision.AskHuman, policy.decide(transfer(amount = "5.0000000001"), zero))
    }

    @Test
    fun `an unparsed submission is never auto-approved`() {
        val policy = DappSpendPolicy(autoApproveBelow = BigDecimal("5"))
        assertEquals(SpendDecision.AskHuman, policy.decide(null, zero))
        assertEquals(SpendDecision.AskHuman, policy.decide(transfer(amount = "not a number"), zero))
    }

    @Test
    fun `disallowed instrument and receiver refuse without asking`() {
        val policy = DappSpendPolicy(
            allowedInstruments = setOf("Amulet"),
            allowedReceivers = setOf("bob::1220bb"),
        )
        assertIs<SpendDecision.Refuse>(policy.decide(transfer(instrument = "OtherCoin"), zero))
        assertIs<SpendDecision.Refuse>(policy.decide(transfer(receiver = "mallory::1220ff"), zero))
        assertEquals(SpendDecision.AskHuman, policy.decide(transfer(), zero))
    }

    @Test
    fun `the per-transaction cap refuses above and allows at the cap`() {
        val policy = DappSpendPolicy(maxPerTransaction = BigDecimal("5"))
        val refused = assertIs<SpendDecision.Refuse>(policy.decide(transfer(amount = "5.01"), zero))
        assertTrue("per-transaction cap" in refused.reason)
        assertEquals(SpendDecision.AskHuman, policy.decide(transfer(amount = "5"), zero))
    }

    @Test
    fun `the daily cap counts what was already spent`() {
        val policy = DappSpendPolicy(dailyCap = BigDecimal("10"))
        assertEquals(SpendDecision.AskHuman, policy.decide(transfer(amount = "4"), BigDecimal("6")))
        val refused = assertIs<SpendDecision.Refuse>(policy.decide(transfer(amount = "4.01"), BigDecimal("6")))
        assertTrue("daily cap" in refused.reason)
    }

    @Test
    fun `auto-approval stays bounded by the caps`() {
        val policy = DappSpendPolicy(
            dailyCap = BigDecimal("10"),
            autoApproveBelow = BigDecimal("5"),
        )
        assertEquals(SpendDecision.AutoApprove, policy.decide(transfer(amount = "2"), BigDecimal("8")))
        assertIs<SpendDecision.Refuse>(policy.decide(transfer(amount = "3"), BigDecimal("8")))
    }
}
