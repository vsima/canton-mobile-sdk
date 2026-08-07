package io.github.vsima.canton.wallet

import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs against a live Canton participant (see integration/run-canton.sh).
 * Skipped unless CANTON_LEDGER_PORT is set.
 *
 * These tests settle the question the mobile wallet architecture hangs on:
 * which key schemes a real participant accepts for *external parties*.
 * Ed25519 is Canton's documented default; EC P-256 is the only scheme
 * Apple's Secure Enclave and Android StrongBox can sign. If the P-256 test
 * passes, enclave-held self-custody keys are viable.
 */
class ExternalPartyIntegrationTest {

    private val port = System.getenv("CANTON_LEDGER_PORT")
    private val host = System.getenv("CANTON_LEDGER_HOST") ?: "127.0.0.1"

    @Test
    fun `allocates an external party with an Ed25519 key`() {
        withLiveLedger { channel ->
            val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.ED25519)
            val party = allocate(channel, driver, "ed25519wallet")
            println("allocated Ed25519 external party: ${party.partyId}")
            assertTrue(party.partyId.startsWith("ed25519wallet::"))
        }
    }

    @Test
    fun `allocates an external party with an EC P-256 key (enclave scheme)`() {
        withLiveLedger { channel ->
            val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
            val party = allocate(channel, driver, "p256wallet")
            println("allocated P-256 external party: ${party.partyId}")
            assertTrue(party.partyId.startsWith("p256wallet::"))
        }
    }

    private fun allocate(
        channel: ManagedChannel,
        driver: SigningDriver,
        hint: String,
    ): AllocatedExternalParty = runBlocking {
        val client = ExternalPartyClient(channel)
        val synchronizer = client.connectedSynchronizers().first()
        client.allocate(driver, synchronizer, hint, userId = "participant_admin")
    }

    private fun withLiveLedger(block: (ManagedChannel) -> Unit) {
        assumeTrue(port != null, "CANTON_LEDGER_PORT not set; skipping live-ledger test")
        val channel = OkHttpChannelBuilder.forAddress(host, port!!.toInt()).usePlaintext().build()
        try {
            block(channel)
        } finally {
            channel.shutdownNow()
        }
    }
}
