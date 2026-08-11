package io.github.vsima.canton.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import io.github.vsima.canton.CantonClient
import io.github.vsima.canton.CantonClientConfiguration
import io.github.vsima.canton.dapp.DappClient
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.DappWalletStatus
import io.github.vsima.canton.dapp.wallet.DappApproval
import io.github.vsima.canton.dapp.wallet.DappApprovalRequest
import io.github.vsima.canton.dapp.wallet.DappNetworkConfig
import io.github.vsima.canton.dapp.wallet.DappPeer
import io.github.vsima.canton.dapp.wallet.DappSession
import io.github.vsima.canton.dapp.wallet.InProcessDappTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal smoke-test app: connects to a Canton participant and shows the
 * Ledger API version. Defaults to 10.0.2.2 (the emulator's host loopback) so
 * it can reach a local `integration/run-canton.sh` node.
 */
class MainActivity : Activity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this)
        status.text = "Connecting to ledger…"
        status.textSize = 16f
        status.setPadding(48, 48, 48, 48)
        // Center in the window: API 35+ lays content out edge-to-edge, so
        // top-aligned text would sit behind the system/app bars.
        status.gravity = Gravity.CENTER
        // Explicit colors so the label is legible regardless of system theme.
        status.setTextColor(0xFF202124.toInt())
        status.setBackgroundColor(0xFFFFFFFF.toInt())
        setContentView(status)

        scope.launch {
            status.text = try {
                val version = withContext(Dispatchers.IO) {
                    CantonClient(
                        CantonClientConfiguration(
                            host = "10.0.2.2",
                            port = 6865,
                            useTls = false,
                        )
                    ).use { it.ledgerApiVersion() }
                }
                // The CIP-0103 layer, exercised in-process: session + client
                // through the real codec and dispatch. Needs no network, and
                // keeps the R8 release build honest about the dapp modules.
                val granted = withContext(Dispatchers.Default) { dappRoundTrip() }
                "Ledger API version: $version\ndApp layer: connected, $granted account(s) granted"
            } catch (e: Exception) {
                "Could not reach ledger: ${e.message}"
            }
        }
    }

    /** Connect → listAccounts through the standard dApp surface. */
    private suspend fun dappRoundTrip(): Int {
        val account = DappWallet(
            primary = true,
            partyId = "sample::1220sample",
            status = DappWalletStatus.ALLOCATED,
            hint = "sample",
            publicKey = "",
            namespace = "1220sample",
            networkId = "canton:sample",
            signingProviderId = "none",
        )
        val session = DappSession(
            peer = DappPeer(id = "sample", name = "Sample app", verified = true),
            accounts = { listOf(account) },
            approver = { request ->
                DappApproval.Approved(
                    accounts = (request as? DappApprovalRequest.Connection)?.available ?: emptyList()
                )
            },
            network = DappNetworkConfig(networkId = "canton:sample"),
        )
        val client = DappClient(InProcessDappTransport(session))
        check(client.connect().isConnected) { "in-process connect failed" }
        return client.listAccounts().size
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
