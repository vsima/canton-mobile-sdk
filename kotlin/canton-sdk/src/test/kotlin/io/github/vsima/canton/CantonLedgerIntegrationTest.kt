package io.github.vsima.canton

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs against a live Canton participant (see integration/run-canton.sh).
 * Skipped unless CANTON_LEDGER_PORT is set.
 */
class CantonLedgerIntegrationTest {

    @Test
    fun `fetches the version from a live canton participant`() {
        val port = System.getenv("CANTON_LEDGER_PORT")
        assumeTrue(port != null, "CANTON_LEDGER_PORT not set; skipping live-ledger test")
        val host = System.getenv("CANTON_LEDGER_HOST") ?: "127.0.0.1"

        runBlocking {
            CantonClient(
                CantonClientConfiguration(host = host, port = port!!.toInt(), useTls = false)
            ).use { client ->
                val version = client.ledgerApiVersion()
                println("live canton ledger api version: $version")
                assertTrue(version.isNotBlank())
            }
        }
    }
}
