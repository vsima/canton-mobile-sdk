// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wc

import io.github.vsima.canton.dapp.DappErrorCode
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.DappWalletStatus
import io.github.vsima.canton.dapp.wallet.DappAccountsSource
import io.github.vsima.canton.dapp.wallet.DappApproval
import io.github.vsima.canton.dapp.wallet.DappApprovalDelegate
import io.github.vsima.canton.dapp.wallet.DappApprovalRequest
import io.github.vsima.canton.dapp.wallet.DappMessageSigner
import io.github.vsima.canton.dapp.wallet.DappNetworkConfig
import io.github.vsima.canton.dapp.wallet.DappPeer
import io.github.vsima.canton.dapp.wallet.DappSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The adapter driven against a real [DappSession] — no relay, no WalletConnect
 * client. This is the point of keeping the client out: the whole approval →
 * sign path is exercised through `handle`, so a failure here is a protocol or
 * mapping failure, deterministically.
 */
class CantonWalletConnectTest {

    private val party =
        "shopper::1220b3d98dd0362a19385d6878be4bafb2f12f13531ee7abcb8f32bdb2d764bac9be"

    private val account = DappWallet(
        primary = true,
        partyId = party,
        status = DappWalletStatus.ALLOCATED,
        hint = "shopper",
        publicKey = "deadbeef",
        namespace = "1220",
        networkId = "canton:localnet",
        signingProviderId = "test",
    )

    private fun session(approver: DappApprovalDelegate) = DappSession(
        peer = DappPeer(id = "dapp1", name = "Test Shop"),
        accounts = DappAccountsSource { listOf(account) },
        approver = approver,
        network = DappNetworkConfig(networkId = "canton:localnet"),
        messageSigner = DappMessageSigner { _, message -> "sig:$message" },
    )

    private val approveAll = DappApprovalDelegate { request ->
        when (request) {
            is DappApprovalRequest.Connection -> DappApproval.Approved(request.available)
            else -> DappApproval.Approved()
        }
    }

    private fun req(id: Long, method: String, params: JsonElement? = null) =
        WcRequest(topic = "topic", requestId = id, chainId = "canton:localnet", method = method, params = params)

    @Test
    fun `sessionNamespaces projects accounts, methods and chain`() {
        val wc = CantonWalletConnect(session(approveAll), "canton:localnet")
        val ns = wc.sessionNamespaces(listOf(account))
        assertEquals(listOf("canton:localnet"), ns.chains)
        assertEquals(listOf("canton:localnet:${Caip.encodeParty(party)}"), ns.accounts)
        assertTrue("canton_signMessage" in ns.methods && "canton_prepareSignExecute" in ns.methods)
        assertEquals(7, ns.methods.size)
        assertEquals(listOf("accountsChanged", "statusChanged"), ns.events)
    }

    @Test
    fun `connect then signMessage returns a signature over the session`() = runBlocking {
        val wc = CantonWalletConnect(session(approveAll), "canton:localnet")
        assertIs<WcResponse.Success>(wc.handle(req(1, "connect")))
        val signed = wc.handle(req(2, "signMessage", buildJsonObject { put("message", "hello canton") }))
        val ok = assertIs<WcResponse.Success>(signed)
        assertEquals("sig:hello canton", ok.result.jsonObject["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an unknown method maps to unsupported-method`() = runBlocking {
        val wc = CantonWalletConnect(session(approveAll), "canton:localnet")
        val err = assertIs<WcResponse.Error>(wc.handle(req(1, "bogus")))
        assertEquals(DappErrorCode.UNSUPPORTED_METHOD.code, err.code)
    }

    @Test
    fun `signMessage before connect is unauthorized`() = runBlocking {
        val wc = CantonWalletConnect(session(approveAll), "canton:localnet")
        val err = assertIs<WcResponse.Error>(
            wc.handle(req(1, "signMessage", buildJsonObject { put("message", "hi") })),
        )
        assertEquals(DappErrorCode.UNAUTHORIZED.code, err.code)
    }

    @Test
    fun `a declined signMessage maps to user-rejected`() = runBlocking {
        val approver = DappApprovalDelegate { request ->
            when (request) {
                is DappApprovalRequest.Connection -> DappApproval.Approved(request.available)
                else -> DappApproval.Rejected("no thanks")
            }
        }
        val wc = CantonWalletConnect(session(approver), "canton:localnet")
        assertIs<WcResponse.Success>(wc.handle(req(1, "connect")))
        val err = assertIs<WcResponse.Error>(
            wc.handle(req(2, "signMessage", buildJsonObject { put("message", "hi") })),
        )
        assertEquals(DappErrorCode.USER_REJECTED.code, err.code)
    }

    @Test
    fun `normalize maps the canton_ prefix and the prepareSignExecute rename`() {
        assertEquals("signMessage", WcMethod.normalize("canton_signMessage"))
        assertEquals("prepareExecuteAndWait", WcMethod.normalize("canton_prepareSignExecute"))
        assertEquals("status", WcMethod.normalize("canton_status"))
        assertEquals("signMessage", WcMethod.normalize("signMessage")) // a bare name passes through
        assertEquals("connect", WcMethod.normalize("connect"))
    }

    @Test
    fun `a canton_ prefixed request is normalized and answered`() = runBlocking {
        val wc = CantonWalletConnect(session(approveAll), "canton:localnet")
        assertIs<WcResponse.Success>(wc.handle(req(1, "canton_connect")))
        val signed = wc.handle(req(2, "canton_signMessage", buildJsonObject { put("message", "hi") }))
        val ok = assertIs<WcResponse.Success>(signed)
        assertEquals("sig:hi", ok.result.jsonObject["signature"]?.jsonPrimitive?.content)
    }
}
