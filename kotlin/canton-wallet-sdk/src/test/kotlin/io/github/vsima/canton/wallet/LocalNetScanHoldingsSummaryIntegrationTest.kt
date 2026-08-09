// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs [ScanClient.holdingsSummary] against Splice LocalNet's live scan.
 * Skipped unless SPLICE_LOCALNET=1.
 *
 * Scan answers from periodic ACS snapshots (hours apart on LocalNet), so
 * this deliberately does NOT tap-and-expect-instant-consistency. Instead it
 * asserts against the validator operator's wallet party, whose holdings
 * long predate the latest snapshot — polling briefly in case the scan is
 * still taking its first snapshot after a fresh boot.
 */
class LocalNetScanHoldingsSummaryIntegrationTest {

    private val enabled = System.getenv("SPLICE_LOCALNET") == "1"

    @Test
    fun `operator wallet totals are present and positive in the latest snapshot`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        val support = LocalNetTokenStandardIntegrationTest()
        runBlocking {
            val scan = ScanClient(
                System.getenv("SPLICE_LOCALNET_SCAN_URL") ?: "http://scan.localhost:4000/api/scan",
                support.http,
            )
            val walletParty = support.onboardWalletUser()
            val dso = scan.dsoPartyId()
            println("wallet party: $walletParty")

            // Poll with a deadline: tolerate a scan that hasn't taken its
            // first snapshot yet, never instant consistency.
            var result: ScanClient.HoldingsSummaryResult? = null
            for (attempt in 1..24) {
                result = scan.holdingsSummary(listOf(walletParty, dso))
                if (result?.summaries?.any { it.partyId == walletParty } == true) break
                println("  (attempt $attempt: no snapshot summary for the wallet party yet)")
                delay(5_000)
            }
            val summaries = result ?: fail("scan never produced an ACS snapshot")
            println(
                "summary: record_time=${summaries.recordTime} migration=${summaries.migrationId} " +
                    summaries.summaries.map { "${it.partyId.take(24)}…=${it.totalCoinHoldings}" }
            )

            // The snapshot is server-side state: it must not postdate now.
            assertTrue(
                summaries.recordTime <= java.time.Instant.now(),
                "snapshot record time must not be in the future",
            )
            assertEquals(scan.latestMigrationId(), summaries.migrationId)

            val operator = summaries.summaries.singleOrNull { it.partyId == walletParty }
                ?: fail("operator wallet party missing from the snapshot summary")
            assertTrue(
                operator.totalCoinHoldings > BigDecimal.ZERO,
                "operator wallet must show positive holdings, got ${operator.totalCoinHoldings}",
            )
            assertEquals(
                operator.totalUnlockedCoin + operator.totalLockedCoin,
                operator.totalCoinHoldings,
            )

            // Pinning the migration id explicitly answers identically.
            val pinned = scan.holdingsSummary(
                ownerPartyIds = listOf(walletParty),
                asOf = summaries.recordTime,
                migrationId = summaries.migrationId,
            ) ?: fail("pinned re-read must find the same snapshot")
            assertEquals(summaries.recordTime, pinned.recordTime)
            assertEquals(operator, pinned.summaries.single())

            // No snapshot can exist before genesis: the read reports that as null.
            assertEquals(
                null,
                scan.holdingsSummary(
                    ownerPartyIds = listOf(walletParty),
                    asOf = java.time.Instant.parse("2000-01-01T00:00:00Z"),
                ),
            )
        }
    }
}
