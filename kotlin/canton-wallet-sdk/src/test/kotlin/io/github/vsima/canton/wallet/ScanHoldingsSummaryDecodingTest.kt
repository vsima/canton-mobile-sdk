// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Decodes the shared fixture (testdata/scan/holdings-summary-v1.json) — a
 * captured `/v1/holdings/summary` response from a live Splice LocalNet scan.
 * The Swift twin consumes the same fixture; both SDKs must agree.
 */
class ScanHoldingsSummaryDecodingTest {

    private val fixture: JsonObject =
        Json.parseToJsonElement(File("../../testdata/scan/holdings-summary-v1.json").readText())
            .jsonObject

    @Test
    fun `decodes the captured live response`() {
        val result = ScanClient.decodeHoldingsSummaryResult(fixture)

        assertEquals(Instant.parse("2026-08-09T18:00:00Z"), result.recordTime)
        assertEquals(0L, result.migrationId)
        // Three parties were queried; the never-funded one is absent.
        assertEquals(2, result.summaries.size)

        val operator = result.summaries.first()
        assertEquals(
            "app_user_localnet-localparty-1::12206e297fb60b09f7a0ae0cc6f81b672c69ca04a72fc34042f5a6364967ab87d7d0",
            operator.partyId,
        )
        assertEquals(BigDecimal("6111277.1600000000"), operator.totalUnlockedCoin)
        assertEquals(BigDecimal("226.0000000000"), operator.totalLockedCoin)
        assertEquals(BigDecimal("6111503.1600000000"), operator.totalCoinHoldings)

        val receiver = result.summaries.last()
        assertEquals(
            "historyreceiver::1220e9bf928f82e3f2208ddbea88165bd13e9b6da4892510f3bda11066da4b672425",
            receiver.partyId,
        )
        assertEquals(BigDecimal("5.0000000000"), receiver.totalUnlockedCoin)
        assertEquals(BigDecimal("0.0000000000"), receiver.totalLockedCoin)
        assertEquals(BigDecimal("5.0000000000"), receiver.totalCoinHoldings)
    }

    @Test
    fun `an empty summaries array decodes to an empty result`() {
        val result = ScanClient.decodeHoldingsSummaryResult(
            Json.parseToJsonElement(
                """{"record_time":"2026-08-09T18:00:00Z","migration_id":4,"summaries":[]}"""
            ).jsonObject
        )
        assertEquals(4L, result.migrationId)
        assertEquals(emptyList(), result.summaries)
    }

    @Test
    fun `malformed payloads raise ScanException, not silent zeros`() {
        assertFailsWith<ScanException> {
            ScanClient.decodeHoldingsSummaryResult(
                Json.parseToJsonElement("""{"migration_id":0,"summaries":[]}""").jsonObject
            )
        }
        assertFailsWith<ScanException> {
            ScanClient.decodeHoldingsSummaryResult(
                Json.parseToJsonElement(
                    """
                    {"record_time":"2026-08-09T18:00:00Z","migration_id":0,"summaries":[
                      {"party_id":"p::1220aa","total_unlocked_coin":"not-a-number",
                       "total_locked_coin":"0","total_coin_holdings":"0"}
                    ]}
                    """
                ).jsonObject
            )
        }
    }
}
