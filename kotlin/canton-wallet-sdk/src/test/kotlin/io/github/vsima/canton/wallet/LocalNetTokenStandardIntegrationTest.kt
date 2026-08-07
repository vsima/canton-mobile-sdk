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
import okhttp3.Request
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
 *  1. tap Amulet to the app-user wallet party (validator API)
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

    private val http = OkHttpClient.Builder()
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
    }

    // -- validator (wallet) API -------------------------------------------

    private fun onboardWalletUser(): String {
        val status = validatorGet("v0/wallet/user-status")
        if (status?.get("party_id")?.jsonPrimitive?.content?.isNotEmpty() == true &&
            status["user_onboarded"]?.jsonPrimitive?.content == "true"
        ) {
            return status.getValue("party_id").jsonPrimitive.content
        }
        val registered = retryUntilBlocking("wallet user onboarding") {
            validatorPost("v0/register", "{}")
        }
        return registered.getValue("party_id").jsonPrimitive.content
    }

    private fun tap(amount: String) {
        retryUntilBlocking("tap $amount (waits for an open mining round)") {
            validatorPost("v0/wallet/tap", """{"amount": "$amount"}""")
        }
    }

    private fun validatorGet(path: String) = httpJson(
        Request.Builder().url("$validatorApi/$path").get()
    )

    private fun validatorPost(path: String, body: String) = httpJson(
        Request.Builder().url("$validatorApi/$path")
            .post(body.toRequestBody("application/json".toMediaType()))
    )

    private fun httpJson(request: Request.Builder): kotlinx.serialization.json.JsonObject? {
        val response = http.newCall(
            request.header("Authorization", "Bearer ${jwt(walletUser)}").build()
        ).execute()
        response.use {
            if (!it.isSuccessful) {
                println("  (validator API ${it.code}: ${it.body?.string()?.take(200)})")
                return null
            }
            val text = it.body?.string().orEmpty()
            if (text.isBlank()) return kotlinx.serialization.json.JsonObject(emptyMap())
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    // -- plumbing ----------------------------------------------------------

    /** Unsafe HS256 JWT matching LocalNet's `unsafe-jwt-hmac-256` auth service. */
    private fun jwt(sub: String): String {
        fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val header = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = b64("""{"sub":"$sub","aud":"$audience"}""".toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("unsafe".toByteArray(), "HmacSHA256"))
        return "$header.$payload.${b64(mac.doFinal("$header.$payload".toByteArray()))}"
    }

    private fun authed(channel: Channel, sub: String): Channel {
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

    private fun <T : Any> retryUntilBlocking(what: String, block: () -> T?): T =
        runBlocking { retryUntil(what) { block() } }
}
