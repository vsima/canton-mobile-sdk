// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The traffic-purchase loop against Splice LocalNet. Skipped unless
 * SPLICE_LOCALNET=1 (see [LocalNetTokenStandardIntegrationTest] for the
 * environment).
 *
 * The full loop, all through the public SDK surface:
 *  1. resolve the active synchronizer and the wallet party's participant
 *     ([ScanClient.amuletRulesConfig], [ScanClient.partyParticipantId])
 *  2. read the participant's traffic status ([ScanClient.memberTrafficStatus])
 *  3. tap enough USD to cover the minimum top-up, then
 *     [ValidatorClient.buyTraffic] for exactly `minTopupAmount` bytes
 *  4. poll [ValidatorClient.buyTrafficStatus] until the wallet automation
 *     reports the purchase completed
 *  5. poll traffic status until the purchased total reflects the bought
 *     bytes.
 */
class LocalNetTrafficIntegrationTest {

    private val enabled = System.getenv("SPLICE_LOCALNET") == "1"

    @Test
    fun `buying the minimum top-up increases the participant's purchased traffic`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        val support = LocalNetTokenStandardIntegrationTest()
        val scanUrl = System.getenv("SPLICE_LOCALNET_SCAN_URL") ?: "http://scan.localhost:4000/api/scan"

        runBlocking {
            val scan = ScanClient(scanUrl, support.http)

            // 1. Where to buy: the active synchronizer; whose traffic: the
            // participant hosting the wallet party.
            val config = scan.amuletRulesConfig()
            val synchronizerId = config.activeSynchronizerId
            val minTopup = config.synchronizerFees.minTopupAmountBytes
            println("synchronizer: $synchronizerId, minTopupAmount: $minTopup bytes")

            val walletParty = support.onboardWalletUser()
            val memberId = retryUntil("participant id resolves") {
                scan.partyParticipantId(synchronizerId, walletParty)
            }
            println("member: $memberId")
            assertTrue(memberId.startsWith("PAR::"), "member must be a participant id")

            // 2. The starting traffic state.
            val before = retryUntil("traffic status readable") {
                scan.memberTrafficStatus(synchronizerId, memberId)
            }
            println("before: $before")

            // An unknown tracking id answers null, not an error.
            assertNull(support.validator.buyTrafficStatus("never-created-${java.util.UUID.randomUUID()}"))

            // 3. Fund the purchase (minTopup bytes ≈ $3.33 at LocalNet's
            // extraTrafficPrice) and request it.
            support.tap("25.0")
            val request = support.validator.buyTraffic(
                trafficAmountBytes = minTopup,
                receivingValidatorPartyId = walletParty,
                synchronizerId = synchronizerId,
            )
            println("buy-traffic request: ${request.requestContractId.take(20)}… tracking=${request.trackingId}")
            assertTrue(request.requestContractId.isNotEmpty())

            // 4. The wallet automation executes it asynchronously.
            val completed = retryUntil("buy-traffic request completes", attempts = 36) {
                when (val status = support.validator.buyTrafficStatus(request.trackingId)) {
                    is ValidatorClient.BuyTrafficStatus.Completed -> status
                    is ValidatorClient.BuyTrafficStatus.Failed ->
                        fail("buy-traffic failed: ${status.reason} ${status.rejectionReason ?: ""}")
                    else -> {
                        println("  (buy-traffic status: $status)")
                        null
                    }
                }
            }
            println("completed in transaction ${completed.transactionId}")
            assertTrue(completed.transactionId.isNotEmpty())

            // 5. The purchase reflects in the member's traffic totals.
            val after = retryUntil("purchased traffic reflects the top-up", attempts = 36) {
                scan.memberTrafficStatus(synchronizerId, memberId)?.takeIf {
                    it.totalPurchasedBytes >= before.totalPurchasedBytes + minTopup
                }
            }
            println("after: $after")
            assertTrue(
                after.totalPurchasedBytes >= before.totalPurchasedBytes + minTopup,
                "purchased must grow by ≥ $minTopup: before=${before.totalPurchasedBytes} " +
                    "after=${after.totalPurchasedBytes}",
            )
        }
    }

    private suspend fun <T : Any> retryUntil(
        what: String,
        attempts: Int = 120,
        delayMs: Long = 5_000,
        block: suspend () -> T?,
    ): T {
        repeat(attempts) { attempt ->
            val result = runCatching { block() }.onFailure {
                if (it is AssertionError) throw it
                println("  ($what attempt ${attempt + 1}: ${it.message?.take(160)})")
            }.getOrNull()
            if (result != null) return result
            delay(delayMs)
        }
        fail("$what: not satisfied after $attempts attempts")
    }
}
