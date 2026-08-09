// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CommandServiceGrpcKt
import com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitRequest
import com.daml.ledger.api.v2.CommandsOuterClass
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import java.math.BigDecimal
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the CIP-0056 client against Splice LocalNet — a real Amulet registry.
 * Skipped unless SPLICE_LOCALNET=1 (see integration/.cache/splice; start with
 * `docker compose --profile sv --profile app-user --profile app-provider up -d`).
 *
 * The full loop this verifies, all through the public SDK surface:
 *  1. tap Amulet to the app-user wallet party ([ValidatorClient.tap])
 *  2. [listHoldings] decodes real Amulet holdings from the interface-filtered ACS
 *  3. allocate a P-256 external party on a Splice participant (WP1 on Splice)
 *  4. token-standard transfer wallet party → external party via the scan
 *     registry's transfer factory (internal-party submission with disclosed
 *     contracts)
 *  5. [pendingTransferInstructions] shows the offer in the external party's inbox
 *  6. [exerciseTransferInstruction] ACCEPT, signed by the P-256 driver
 *  7. [listHoldings] shows the external party's Amulet
 */
class LocalNetTokenStandardIntegrationTest {

    private val enabled = System.getenv("SPLICE_LOCALNET") == "1"

    private val ledgerHost = env("SPLICE_LOCALNET_LEDGER_HOST", "127.0.0.1")
    private val ledgerPort = env("SPLICE_LOCALNET_LEDGER_PORT", "2901").toInt()
    private val validatorApi = env("SPLICE_LOCALNET_VALIDATOR_URL", "http://wallet.localhost:2000/api/validator")
    // The token-standard registry endpoints are mounted at the scan vhost
    // root (nginx: `location /registry`), not under /api/scan.
    private val registryUrl = env("SPLICE_LOCALNET_REGISTRY_URL", "http://scan.localhost:4000")
    private val audience = env("SPLICE_LOCALNET_AUDIENCE", "https://canton.network.global")
    private val adminUser = env("SPLICE_LOCALNET_ADMIN_USER", "ledger-api-user")
    private val walletUser = env("SPLICE_LOCALNET_WALLET_USER", "app-user")

    private fun env(name: String, default: String) = System.getenv(name) ?: default

    /** Everything *.localhost is loopback; Java's resolver can't be trusted for it. */
    private val loopbackLocalhostDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.endsWith(".localhost")) listOf(InetAddress.getLoopbackAddress())
            else Dns.SYSTEM.lookup(hostname)
    }

    internal val http = OkHttpClient.Builder()
        .dns(loopbackLocalhostDns)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Test
    fun `full token-standard loop against a live Amulet registry`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        runBlocking {
            val plain = OkHttpChannelBuilder.forAddress(ledgerHost, ledgerPort).usePlaintext().build()
            try {
                run(plain)
            } finally {
                plain.shutdownNow()
            }
        }
    }

    private suspend fun run(plain: ManagedChannel) {
        val adminChannel = authed(plain, adminUser)
        val walletChannel = authed(plain, walletUser)

        // 1. Onboard the wallet user (idempotent) and tap Amulet.
        val walletParty = onboardWalletUser()
        println("wallet party: $walletParty")
        tap("777.0")

        // 2. Our client reads real Amulet holdings.
        val registry = TransferRegistryClient(registryUrl, http)
        val walletTokens = TokenStandardClient(walletChannel, registry)
        val holdings = retryUntil("wallet holdings visible") {
            walletTokens.listHoldings(walletParty).ifEmpty { null }
        }
        val amulet = holdings.first().instrumentId
        println("holdings: ${holdings.map { "${it.amount} ${it.instrumentId.id}" }}")
        assertEquals("Amulet", amulet.id)

        // 3. P-256 external party on a Splice participant.
        val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
        val parties = ExternalPartyClient(adminChannel)
        val synchronizer = parties.connectedSynchronizers().first()
        val external = parties.allocate(driver, synchronizer, "localnetp256", userId = adminUser)
        println("external party: ${external.partyId}")

        // 4. Token-standard transfer wallet party -> external party. The wallet
        // party is participant-managed, so this leg submits via the command
        // service — with the registry's factory + disclosed contracts.
        // Amulet's factory requires the sender's input UTXOs explicitly.
        val inputs = holdings.filter { it.lock == null }.map { it.contractId }
        assertTrue(inputs.isNotEmpty(), "no unlocked holdings to spend")
        val transfer = Transfer(
            sender = walletParty,
            receiver = external.partyId,
            amount = BigDecimal("5.0"),
            instrumentId = amulet,
            requestedAt = java.time.Instant.now(),
            executeBefore = java.time.Instant.now().plusSeconds(24 * 3600),
            inputHoldingCids = inputs,
            meta = emptyMap(),
        )
        val factory = registry.transferFactory(
            ChoiceContextJson.transferFactoryChoiceArguments(amulet.admin, transfer)
        )
        println("factory: ${factory.factoryId.take(20)}… kind=${factory.transferKind}")

        val exercise = CommandsOuterClass.Command.newBuilder()
            .setExercise(
                CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(TokenStandard.transferFactoryInterfaceId)
                    .setContractId(factory.factoryId)
                    .setChoice("TransferFactory_Transfer")
                    .setChoiceArgument(
                        io.github.vsima.canton.DamlValues.record(
                            "expectedAdmin" to io.github.vsima.canton.DamlValues.party(amulet.admin),
                            "transfer" to transfer.toValue(),
                            "extraArgs" to ChoiceContextJson.extraArgsValue(
                                factory.choiceContext.choiceContextData
                            ),
                        )
                    )
            )
            .build()
        CommandServiceGrpcKt.CommandServiceCoroutineStub(walletChannel).submitAndWait(
            SubmitAndWaitRequest.newBuilder()
                .setCommands(
                    CommandsOuterClass.Commands.newBuilder()
                        .setCommandId(java.util.UUID.randomUUID().toString())
                        .setUserId(walletUser)
                        .addActAs(walletParty)
                        .addCommands(exercise)
                        .addAllDisclosedContracts(
                            factory.choiceContext.disclosedContracts.map { it.toProto() }
                        )
                )
                .build()
        )

        // 5. The offer lands in the external party's inbox.
        val externalTokens = TokenStandardClient(adminChannel, registry)
        val instruction = retryUntil("transfer instruction in inbox") {
            externalTokens.pendingTransferInstructions(external.partyId).firstOrNull()
        }
        println("instruction: ${instruction.contractId.take(20)}… status=${instruction.status}")
        assertEquals(TransferInstructionStatus.PendingReceiverAcceptance, instruction.status)
        assertEquals(walletParty, instruction.transfer.sender)

        // 6. Accept it — externally signed by the P-256 driver.
        externalTokens.exerciseTransferInstruction(
            driver = driver,
            party = external,
            transferInstructionId = instruction.contractId,
            choice = TransferInstructionChoice.ACCEPT,
            synchronizerId = synchronizer,
            userId = adminUser,
        )

        // 7. The external party now holds Amulet.
        val received = retryUntil("external party holdings visible") {
            externalTokens.listHoldings(external.partyId).ifEmpty { null }
        }
        println("external holdings: ${received.map { "${it.amount} ${it.instrumentId.id}" }}")
        assertEquals("Amulet", received.first().instrumentId.id)
        assertTrue(received.sumOf { it.amount } >= BigDecimal("4.0"))

        // 8. WP3: the parsed holdings history shows the credit.
        val history = externalTokens.holdingsHistory(external.partyId)
        println("history: ${history.map { c -> "${c.created.map { it.amount }}/${c.archivedContractIds.size} archived" }}")
        assertTrue(history.isNotEmpty(), "expected holdings history for the external party")
        val credited = history.flatMap { it.created }.sumOf { it.amount }
        assertTrue(credited >= BigDecimal("4.0"), "history credits should cover the transfer")

        // The sender's history must show inputs being archived by the transfer.
        val senderHistory = walletTokens.holdingsHistory(walletParty)
        assertTrue(
            senderHistory.any { it.archivedContractIds.isNotEmpty() },
            "sender history should contain archived input holdings",
        )

        // 9. WP3: ANS resolution against the live scan.
        val scan = ScanClient(env("SPLICE_LOCALNET_SCAN_URL", "http://scan.localhost:4000/api/scan"), http)
        val dso = scan.dsoPartyId()
        val dsoEntry = scan.lookupAnsEntryByName("dso.ans")
        println("ans: dso.ans -> ${dsoEntry?.party?.take(20)}…, dso=$${dso.take(20)}…")
        assertEquals(dso, dsoEntry?.party)
        assertEquals(amulet.admin, dso)
        assertTrue(scan.lookupAnsEntryByName("definitely-not-registered.ans") == null)
        assertTrue(scan.listAnsEntries(pageSize = 10).any { it.name == "dso.ans" })
    }

    /**
     * The preapproval loop: an external party requests its own preapproval
     * (externally signed); the provider's validator automation accepts and
     * pays; from then on transfers to it settle in one step — the registry
     * routes "direct" and nothing lands in the inbox.
     */
    @Test
    fun `preapproved external party receives direct transfers with no inbox step`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        runBlocking {
            val plain = OkHttpChannelBuilder.forAddress(ledgerHost, ledgerPort).usePlaintext().build()
            try {
                val adminChannel = authed(plain, adminUser)
                val walletChannel = authed(plain, walletUser)
                val registry = TransferRegistryClient(registryUrl, http)
                val scan = ScanClient(
                    env("SPLICE_LOCALNET_SCAN_URL", "http://scan.localhost:4000/api/scan"),
                    http,
                )

                val walletParty = onboardWalletUser()
                tap("333.0")
                val walletTokens = TokenStandardClient(walletChannel, registry)
                val amulet = retryUntil("wallet holdings visible") {
                    walletTokens.listHoldings(walletParty).ifEmpty { null }
                }.first().instrumentId

                // External party requests its own preapproval, externally signed.
                // On LocalNet the wallet party IS the validator operator, so it
                // is the provider that accepts and pays.
                val driver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
                val parties = ExternalPartyClient(adminChannel)
                val synchronizer = parties.connectedSynchronizers().first()
                val external = parties.allocate(driver, synchronizer, "preapproved", userId = adminUser)
                println("preapproval external party: ${external.partyId}")

                val externalTokens = TokenStandardClient(adminChannel, registry)
                externalTokens.requestTransferPreapproval(
                    driver = driver,
                    party = external,
                    provider = walletParty,
                    dso = scan.dsoPartyId(),
                    synchronizerId = synchronizer,
                    userId = adminUser,
                )

                val preapproval = retryUntil("validator automation accepts the preapproval", attempts = 36) {
                    scan.transferPreapprovalByParty(external.partyId)
                }
                println(
                    "preapproval: ${preapproval.contractId.take(20)}… " +
                        "provider=${preapproval.provider?.take(24)}…"
                )

                // Same factory flow as the offer test — but the registry now
                // routes it directly.
                val transfer = Transfer(
                    sender = walletParty,
                    receiver = external.partyId,
                    amount = BigDecimal("3.0"),
                    instrumentId = amulet,
                    requestedAt = java.time.Instant.now(),
                    executeBefore = java.time.Instant.now().plusSeconds(24 * 3600),
                    inputHoldingCids = walletTokens.listHoldings(walletParty)
                        .filter { it.lock == null }.map { it.contractId },
                    meta = emptyMap(),
                )
                val factory = registry.transferFactory(
                    ChoiceContextJson.transferFactoryChoiceArguments(amulet.admin, transfer)
                )
                println("factory kind=${factory.transferKind}")
                assertEquals("direct", factory.transferKind)

                val exercise = CommandsOuterClass.Command.newBuilder()
                    .setExercise(
                        CommandsOuterClass.ExerciseCommand.newBuilder()
                            .setTemplateId(TokenStandard.transferFactoryInterfaceId)
                            .setContractId(factory.factoryId)
                            .setChoice("TransferFactory_Transfer")
                            .setChoiceArgument(
                                io.github.vsima.canton.DamlValues.record(
                                    "expectedAdmin" to io.github.vsima.canton.DamlValues.party(amulet.admin),
                                    "transfer" to transfer.toValue(),
                                    "extraArgs" to ChoiceContextJson.extraArgsValue(
                                        factory.choiceContext.choiceContextData
                                    ),
                                )
                            )
                    )
                    .build()
                CommandServiceGrpcKt.CommandServiceCoroutineStub(walletChannel).submitAndWait(
                    SubmitAndWaitRequest.newBuilder()
                        .setCommands(
                            CommandsOuterClass.Commands.newBuilder()
                                .setCommandId(java.util.UUID.randomUUID().toString())
                                .setUserId(walletUser)
                                .addActAs(walletParty)
                                .addCommands(exercise)
                                .addAllDisclosedContracts(
                                    factory.choiceContext.disclosedContracts.map { it.toProto() }
                                )
                        )
                        .build()
                )

                // One step: holdings arrive with no inbox entry to accept.
                val received = retryUntil("preapproved holdings visible") {
                    externalTokens.listHoldings(external.partyId).ifEmpty { null }
                }
                println("preapproved holdings: ${received.map { "${it.amount} ${it.instrumentId.id}" }}")
                assertTrue(received.sumOf { it.amount } >= BigDecimal("2.0"))
                assertTrue(
                    externalTokens.pendingTransferInstructions(external.partyId).isEmpty(),
                    "direct transfer must not create an inbox entry",
                )
            } finally {
                plain.shutdownNow()
            }
        }
    }

    /**
     * The SDK-level tap: [ValidatorClient.tap] mints the requested USD
     * value to the authenticated user's wallet party and returns the minted
     * holding's contract id, which must show up in [TokenStandardClient.listHoldings]
     * carrying exactly `amountUsd / amuletPrice` — Splice's own conversion
     * at the open mining round's price.
     */
    @Test
    fun `SDK-level tap mints the requested USD value to the wallet party`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        runBlocking {
            val plain = OkHttpChannelBuilder.forAddress(ledgerHost, ledgerPort).usePlaintext().build()
            try {
                val walletChannel = authed(plain, walletUser)
                val walletParty = onboardWalletUser()
                val registry = TransferRegistryClient(registryUrl, http)
                val tokens = TokenStandardClient(walletChannel, registry)
                val before = tokens.listHoldings(walletParty).sumOf { it.amount }

                // Distinctive USD value: nothing else on LocalNet taps this.
                val amountUsd = BigDecimal("123.4567")
                val mintedCid = tap(amountUsd.toPlainString())
                println("tap minted contract: ${mintedCid.take(20)}…")

                val holdings = retryUntil("tapped holding visible in the ACS") {
                    tokens.listHoldings(walletParty)
                        .takeIf { all -> all.any { it.contractId == mintedCid } }
                }
                val minted = holdings.first { it.contractId == mintedCid }
                println("minted holding: ${minted.amount} ${minted.instrumentId.id}")
                assertEquals("Amulet", minted.instrumentId.id)

                // The tap is USD-denominated: minted CC = amountUsd / amuletPrice
                // at an open mining round's price, rounded up (the validator's
                // own conversion — HttpWalletHandler.tap).
                val prices = openMiningRoundAmuletPrices()
                println("open round amulet prices: $prices")
                assertTrue(
                    prices.any { price ->
                        minted.amount.compareTo(
                            amountUsd.divide(price, java.math.RoundingMode.CEILING)
                        ) == 0
                    },
                    "minted ${minted.amount} must be $amountUsd USD / an open round price in $prices",
                )
                val after = holdings.sumOf { it.amount }
                assertTrue(
                    after >= before + minted.amount,
                    "holdings must increase by the minted amount: before=$before after=$after",
                )
            } finally {
                plain.shutdownNow()
            }
        }
    }

    /** The distinct amulet prices on the currently-open mining rounds, via scan. */
    private fun openMiningRoundAmuletPrices(): List<BigDecimal> {
        val url = env("SPLICE_LOCALNET_SCAN_URL", "http://scan.localhost:4000/api/scan") +
            "/v0/open-and-issuing-mining-rounds"
        val body =
            """{"cached_open_mining_round_contract_ids":[],"cached_issuing_round_contract_ids":[]}"""
        http.newCall(
            okhttp3.Request.Builder().url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { response ->
            check(response.isSuccessful) { "open rounds read failed: HTTP ${response.code}" }
            return Json.parseToJsonElement(response.body!!.string()).jsonObject
                .getValue("open_mining_rounds").jsonObject.values
                .map {
                    BigDecimal(
                        it.jsonObject.getValue("contract").jsonObject
                            .getValue("payload").jsonObject
                            .getValue("amuletPrice").jsonPrimitive.content
                    )
                }
                .distinct()
        }
    }

    // -- validator (wallet) API -------------------------------------------

    /** The public SDK surface the harness taps and onboards through. */
    internal val validator = ValidatorClient(validatorApi, { jwt(walletUser) }, http)

    internal suspend fun onboardWalletUser(): String {
        val status = runCatching { validator.userStatus() }.getOrNull()
        if (status != null && status.userOnboarded && status.partyId.isNotEmpty()) {
            return status.partyId
        }
        return retryUntil("wallet user onboarding") { validator.register() }
    }

    /** Taps [amountUsd] (USD) to the operator wallet, returning the minted contract id. */
    internal suspend fun tap(amountUsd: String): String =
        retryUntil("tap $amountUsd USD (waits for an open mining round)") {
            validator.tap(BigDecimal(amountUsd))
        }

    // -- plumbing ----------------------------------------------------------

    /** Unsafe HS256 JWT matching LocalNet's `unsafe-jwt-hmac-256` auth service. */
    internal fun jwt(sub: String): String {
        fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val header = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = b64("""{"sub":"$sub","aud":"$audience"}""".toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("unsafe".toByteArray(), "HmacSHA256"))
        return "$header.$payload.${b64(mac.doFinal("$header.$payload".toByteArray()))}"
    }

    internal fun authed(channel: Channel, sub: String): Channel {
        val token = jwt(sub)
        val interceptor = object : ClientInterceptor {
            override fun <ReqT, RespT> interceptCall(
                method: MethodDescriptor<ReqT, RespT>,
                callOptions: CallOptions,
                next: Channel,
            ): ClientCall<ReqT, RespT> =
                object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)
                ) {
                    override fun start(listener: Listener<RespT>, headers: Metadata) {
                        headers.put(
                            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                            "Bearer $token",
                        )
                        super.start(listener, headers)
                    }
                }
        }
        return io.grpc.ClientInterceptors.intercept(channel, interceptor)
    }

    private suspend fun <T : Any> retryUntil(
        what: String,
        attempts: Int = 120,
        delayMs: Long = 5_000,
        block: suspend () -> T?,
    ): T {
        repeat(attempts) { attempt ->
            val result = runCatching { block() }.onFailure {
                println("  ($what attempt ${attempt + 1}: ${it.message?.take(160)})")
            }.getOrNull()
            if (result != null) return result
            delay(delayMs)
        }
        fail("$what: not satisfied after $attempts attempts")
    }
}
