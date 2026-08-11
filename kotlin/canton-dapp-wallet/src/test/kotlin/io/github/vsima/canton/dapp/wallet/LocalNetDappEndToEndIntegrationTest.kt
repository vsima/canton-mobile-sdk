// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import com.daml.ledger.api.v2.CommandServiceGrpcKt
import com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitRequest
import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PrepareSubmissionResponse
import io.github.vsima.canton.DamlValues
import io.github.vsima.canton.dapp.DappClient
import io.github.vsima.canton.dapp.DappEvent
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.DappWalletStatus
import io.github.vsima.canton.dapp.LedgerApiMethod
import io.github.vsima.canton.dapp.LedgerApiRequest
import io.github.vsima.canton.dapp.PrepareSubmission
import io.github.vsima.canton.dapp.TxChangedEvent
import io.github.vsima.canton.wallet.ChoiceContextJson
import io.github.vsima.canton.wallet.ExternalPartyClient
import io.github.vsima.canton.wallet.InstrumentId
import io.github.vsima.canton.wallet.InteractiveSubmissionClient
import io.github.vsima.canton.wallet.PreparedTransactionHash
import io.github.vsima.canton.wallet.SoftwareSigningDriver
import io.github.vsima.canton.wallet.TokenStandard
import io.github.vsima.canton.wallet.TokenStandardClient
import io.github.vsima.canton.wallet.Transfer
import io.github.vsima.canton.wallet.TransferInstructionChoice
import io.github.vsima.canton.wallet.TransferInstructionStatus
import io.github.vsima.canton.wallet.TransferRegistryClient
import io.github.vsima.canton.wallet.toValue
import io.github.vsima.canton.wallet.ValidatorClient
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import java.math.BigDecimal
import java.net.InetAddress
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * F2's acceptance run: a dApp drives the wallet through the **standard
 * CIP-0103 surface** — `DappClient` → in-process transport → `DappSession` →
 * [JsonPrepareExecutePipeline] — and a real token-standard transfer lands on
 * a live Amulet registry (Splice LocalNet). Skipped unless SPLICE_LOCALNET=1.
 *
 * The dApp side authors its commands the way the JS ecosystem's portfolio
 * example does (spec §4.2): it fetches the registry's transfer factory and
 * choice context itself and submits `commands` + `disclosedContracts`
 * through `prepareExecuteAndWait`. The wallet supplies the envelope, verifies
 * the hash, signs with the P-256 driver, executes over gRPC.
 *
 * Also holds the two §4.3.1 regression assertions:
 *  (a) the prepared transaction decoded out of the JSON response re-hashes to
 *      the participant's own hash ([PreparedTransactionHash.verify]);
 *  (b) the same logical transfer prepared over JSON and over gRPC yields the
 *      **same root exercise** — interface, target contract, choice and the
 *      chosen argument, compared as protos. Full-tree equality is
 *      unachievable by construction (node seeds, transaction uuid,
 *      ledger-effective-time in created payloads), but the root exercise is
 *      exactly the part the dApp authored, so it is where re-encoding drift
 *      would show.
 */
class LocalNetDappEndToEndIntegrationTest {

    private val enabled = System.getenv("SPLICE_LOCALNET") == "1"

    private val ledgerHost = env("SPLICE_LOCALNET_LEDGER_HOST", "127.0.0.1")
    private val ledgerPort = env("SPLICE_LOCALNET_LEDGER_PORT", "2901").toInt()
    private val jsonApiUrl = env("SPLICE_LOCALNET_JSON_API_URL", "http://127.0.0.1:2975")
    private val validatorApi = env("SPLICE_LOCALNET_VALIDATOR_URL", "http://wallet.localhost:2000/api/validator")
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
    fun `a dApp completes a token-standard transfer through the CIP-0103 surface`() {
        assumeTrue(enabled, "SPLICE_LOCALNET not set; skipping LocalNet test")
        runBlocking {
            val plain = OkHttpChannelBuilder.forAddress(ledgerHost, ledgerPort).usePlaintext().build()
            try {
                run(this, plain)
            } finally {
                plain.shutdownNow()
            }
        }
    }

    private suspend fun run(scope: CoroutineScope, plain: ManagedChannel) {
        val adminChannel = authed(plain, adminUser)
        val walletChannel = authed(plain, walletUser)
        val registry = TransferRegistryClient(registryUrl, http)
        val validator = ValidatorClient(validatorApi, { jwt(walletUser) }, http)

        // ── Stage: fund a dApp user ────────────────────────────────────
        // The dApp's user must be an *external* party — participant-managed
        // parties cannot sign via interactive submission, which is the whole
        // pipeline. Tap to the operator wallet, transfer to the external
        // party, accept. All through SDK surfaces already live-tested
        // elsewhere; the dApp leg below is what this test exists for.
        val walletParty = onboardWalletUser(validator)
        tap(validator, "600.0")

        val walletTokens = TokenStandardClient(walletChannel, registry)
        val holdings = retryUntil("wallet holdings visible") {
            walletTokens.listHoldings(walletParty).ifEmpty { null }
        }
        val amulet = holdings.first().instrumentId
        assertEquals("Amulet", amulet.id)

        val parties = ExternalPartyClient(adminChannel)
        val synchronizer = parties.connectedSynchronizers().first()
        val senderDriver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
        val sender = parties.allocate(senderDriver, synchronizer, "dappsender", userId = adminUser)
        val receiverDriver = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
        val receiver = parties.allocate(receiverDriver, synchronizer, "dappreceiver", userId = adminUser)
        println("sender: ${sender.partyId}\nreceiver: ${receiver.partyId}")

        fundExternalParty(walletChannel, walletTokens, registry, walletParty, sender.partyId, amulet)
        val adminTokens = TokenStandardClient(adminChannel, registry)
        val funding = retryUntil("funding offer in sender inbox") {
            adminTokens.pendingTransferInstructions(sender.partyId).firstOrNull()
        }
        adminTokens.exerciseTransferInstruction(
            driver = senderDriver,
            party = sender,
            transferInstructionId = funding.contractId,
            choice = TransferInstructionChoice.ACCEPT,
            synchronizerId = synchronizer,
            userId = adminUser,
        )
        val senderHoldings = retryUntil("sender funded") {
            adminTokens.listHoldings(sender.partyId).ifEmpty { null }
        }
        println("sender holds: ${senderHoldings.sumOf { it.amount }} ${amulet.id}")

        // ── Stage: the dApp authors its commands (spec §4.2) ───────────
        val memo = "Invoice #4021 (dApp e2e)"
        val amount = BigDecimal("7.0")
        // Micros: Daml timestamps are microsecond-precision, and assertion (b)
        // feeds the same instants through two encoders that must agree.
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val transfer = Transfer(
            sender = sender.partyId,
            receiver = receiver.partyId,
            amount = amount,
            instrumentId = amulet,
            requestedAt = now,
            executeBefore = now.plusSeconds(24 * 3600),
            inputHoldingCids = senderHoldings.filter { it.lock == null }.map { it.contractId },
            meta = mapOf(TokenStandard.reasonMetadataKey to memo),
        )
        val transferJson = ChoiceContextJson.transferFactoryChoiceArguments(amulet.admin, transfer)
        val factory = registry.transferFactory(transferJson)
        println("factory: ${factory.factoryId.take(20)}… kind=${factory.transferKind}")

        // The command, in JSON Ledger API shape — what a native dApp built on
        // canton-dapp submits. The registry's choiceContextData is already
        // Daml-JSON, so it passes through verbatim; nothing is re-encoded.
        val interfaceId = TokenStandard.transferFactoryInterfaceId.let {
            "${it.packageId}:${it.moduleName}:${it.entityName}"
        }
        val jsonCommand = buildJsonObject {
            put(
                "ExerciseCommand",
                buildJsonObject {
                    put("templateId", interfaceId)
                    put("contractId", factory.factoryId)
                    put("choice", "TransferFactory_Transfer")
                    put(
                        "choiceArgument",
                        buildJsonObject {
                            transferJson.forEach { (key, value) -> if (key != "extraArgs") put(key, value) }
                            put(
                                "extraArgs",
                                buildJsonObject {
                                    put(
                                        "context",
                                        factory.choiceContext.choiceContextData
                                            ?: buildJsonObject { put("values", buildJsonObject {}) },
                                    )
                                    put("meta", buildJsonObject { put("values", buildJsonObject {}) })
                                },
                            )
                        },
                    )
                },
            )
        }
        val disclosedJson = buildJsonArray {
            for (contract in factory.choiceContext.disclosedContracts) {
                add(
                    buildJsonObject {
                        put("templateId", contract.templateId)
                        put("contractId", contract.contractId)
                        put("createdEventBlob", contract.createdEventBlobBase64)
                        put("synchronizerId", contract.synchronizerId)
                    }
                )
            }
        }

        // ── Assertions (a) and (b), before the inputs are spent ────────
        val jsonLedger = JsonLedgerApiClient(jsonApiUrl, { jwt(adminUser) }, http)
        val submission = InteractiveSubmissionClient(adminChannel)
        val pipeline = JsonPrepareExecutePipeline(
            ledgerApi = jsonLedger,
            submission = submission,
            signer = senderDriver,
            userId = adminUser,
        )
        val probeCommandId = UUID.randomUUID().toString()
        val jsonPrepared: PrepareSubmissionResponse = pipeline.decodePrepared(
            jsonLedger.post(
                "/v2/interactive-submission/prepare",
                buildJsonObject {
                    put("commandId", probeCommandId)
                    put("commands", buildJsonArray { add(jsonCommand) })
                    put("actAs", buildJsonArray { add(JsonPrimitive(sender.partyId)) })
                    put("synchronizerId", synchronizer)
                    put("packageIdSelectionPreference", buildJsonArray {})
                    put("disclosedContracts", disclosedJson)
                    put("userId", adminUser)
                } as JsonObject,
            )
        )
        // (a) The bytes we decoded out of JSON re-hash to the participant's
        // own hash — the claim the whole hybrid design rests on.
        PreparedTransactionHash.verify(jsonPrepared)
        println("(a) hash self-consistency: OK")

        // (b) The same logical transfer prepared over gRPC interprets to the
        // same root exercise.
        val protoCommand = CommandsOuterClass.Command.newBuilder()
            .setExercise(
                CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(TokenStandard.transferFactoryInterfaceId)
                    .setContractId(factory.factoryId)
                    .setChoice("TransferFactory_Transfer")
                    .setChoiceArgument(
                        DamlValues.record(
                            "expectedAdmin" to DamlValues.party(amulet.admin),
                            "transfer" to transfer.toValue(),
                            "extraArgs" to ChoiceContextJson.extraArgsValue(
                                factory.choiceContext.choiceContextData
                            ),
                        )
                    )
            )
            .build()
        val grpcPrepared = submission.prepare(
            commands = listOf(protoCommand),
            actAs = sender.partyId,
            synchronizerId = synchronizer,
            userId = adminUser,
            commandId = probeCommandId,
            disclosedContracts = factory.choiceContext.disclosedContracts.map { it.toProto() },
        )
        assertSameRootExercise(jsonPrepared, grpcPrepared)
        println("(b) cross-transport root exercise: OK")

        // ── Stage: the dApp leg proper ─────────────────────────────────
        val approvals = mutableListOf<DappApprovalRequest>()
        val session = DappSession(
            peer = DappPeer(id = "e2e", name = "LocalNet e2e dApp", verified = true),
            accounts = {
                listOf(
                    DappWallet(
                        primary = true,
                        partyId = sender.partyId,
                        status = DappWalletStatus.ALLOCATED,
                        hint = "dappsender",
                        publicKey = "",
                        // The pipeline's default keyFingerprint reads this.
                        namespace = sender.publicKeyFingerprint,
                        networkId = "canton:localnet",
                        signingProviderId = "software-p256",
                    )
                )
            },
            approver = { request ->
                approvals += request
                DappApproval.Approved(
                    accounts = (request as? DappApprovalRequest.Connection)?.available ?: emptyList()
                )
            },
            network = DappNetworkConfig(
                networkId = "canton:localnet",
                jsonApiBaseUrl = jsonApiUrl,
                synchronizerId = synchronizer,
                accessTokenProvider = { jwt(adminUser) },
            ),
            prepareExecute = pipeline,
            ledgerApi = HttpLedgerApiProxy(jsonLedger),
        )
        val client = DappClient(InProcessDappTransport(session))
        val events = mutableListOf<DappEvent>()
        val collector = scope.collectingEvents(client, events)

        assertTrue(client.connect().isConnected, "dApp must connect")
        val executed = client.prepareExecuteAndWait(
            PrepareSubmission(
                commands = buildJsonArray { add(jsonCommand) },
                disclosedContracts = disclosedJson,
                actAs = listOf(sender.partyId),
            )
        )
        println("executed: updateId=${executed.updateId} offset=${executed.completionOffset}")
        assertTrue(executed.updateId.isNotEmpty(), "completion must carry an update id")

        // The approval sheet saw the transaction, for the right party.
        val txApproval = approvals.filterIsInstance<DappApprovalRequest.Transaction>().single()
        assertEquals(sender.partyId, txApproval.actAs.partyId)
        // The lifecycle reached the dApp: pending first, executed last.
        awaitEvents(events) { list ->
            list.filterIsInstance<DappEvent.TxChanged>().any { it.tx is TxChangedEvent.Executed }
        }
        val txEvents = events.filterIsInstance<DappEvent.TxChanged>().map { it.tx }
        assertTrue(txEvents.first() is TxChangedEvent.Pending, "first tx event should be pending")
        assertTrue(txEvents.last() is TxChangedEvent.Executed, "last tx event should be executed")
        collector.cancel()

        // ── Stage: the transfer really landed ──────────────────────────
        val offer = retryUntil("offer in receiver inbox") {
            adminTokens.pendingTransferInstructions(receiver.partyId).firstOrNull()
        }
        assertEquals(TransferInstructionStatus.PendingReceiverAcceptance, offer.status)
        assertEquals(sender.partyId, offer.transfer.sender)
        assertEquals(memo, offer.transfer.meta[TokenStandard.reasonMetadataKey])

        adminTokens.exerciseTransferInstruction(
            driver = receiverDriver,
            party = receiver,
            transferInstructionId = offer.contractId,
            choice = TransferInstructionChoice.ACCEPT,
            synchronizerId = synchronizer,
            userId = adminUser,
        )
        val received = retryUntil("receiver holdings visible") {
            adminTokens.listHoldings(receiver.partyId).ifEmpty { null }
        }
        assertTrue(
            received.sumOf { it.amount } >= BigDecimal("6.0"),
            "receiver should hold ~$amount minus fees, held ${received.sumOf { it.amount }}",
        )

        // The wallet's own history renders the transfer with the memo — the
        // acceptance criterion's legibility clause.
        val history = retryUntil("memo visible in receiver history") {
            adminTokens.holdingsHistory(receiver.partyId)
                .takeIf { rows -> rows.any { it.summary?.memo == memo } }
        }
        println("history memo row: ${history.first { it.summary?.memo == memo }.summary}")

        // ── Stage: ledgerApi passthrough ───────────────────────────────
        val version = client.ledgerApi(LedgerApiRequest(LedgerApiMethod.GET, "/v2/version"))
        val versionText = version.jsonObject.getValue("version").jsonPrimitive.content
        println("ledgerApi passthrough: version=$versionText")
        assertTrue(versionText.isNotEmpty())
    }

    // ── Assertion (b) helper ───────────────────────────────────────────

    /**
     * The parts of two prepared transactions that must agree when the same
     * logical command went in through two front doors. Everything else —
     * transaction uuid, preparation time, node seeds, created contract ids,
     * ledger-effective-time-bearing payloads — is nondeterministic per
     * prepare, by construction.
     */
    private fun assertSameRootExercise(
        json: PrepareSubmissionResponse,
        grpc: PrepareSubmissionResponse,
    ) {
        val jsonTx = json.preparedTransaction.transaction
        val grpcTx = grpc.preparedTransaction.transaction
        assertEquals(jsonTx.rootsList, grpcTx.rootsList, "root node ids differ")

        val jsonRoot = jsonTx.nodesList.first { it.nodeId == jsonTx.rootsList.first() }
        val grpcRoot = grpcTx.nodesList.first { it.nodeId == grpcTx.rootsList.first() }
        val jsonExercise = jsonRoot.v1.exercise
        val grpcExercise = grpcRoot.v1.exercise
        assertEquals(grpcExercise.contractId, jsonExercise.contractId, "target contract differs")
        assertEquals(grpcExercise.choiceId, jsonExercise.choiceId, "choice differs")
        assertEquals(grpcExercise.interfaceId, jsonExercise.interfaceId, "interface id differs")
        // The chosen argument byte-for-byte: this is where a JSON-path
        // re-encoding of the dApp's Daml values would surface.
        assertEquals(grpcExercise.chosenValue, jsonExercise.chosenValue, "chosen value differs")

        val jsonMeta = json.preparedTransaction.metadata
        val grpcMeta = grpc.preparedTransaction.metadata
        assertEquals(grpcMeta.submitterInfo, jsonMeta.submitterInfo, "submitter info differs")
        assertEquals(grpcMeta.synchronizerId, jsonMeta.synchronizerId, "synchronizer differs")
        assertEquals(
            grpcMeta.inputContractsList.map { it.v1.contractId }.toSet(),
            jsonMeta.inputContractsList.map { it.v1.contractId }.toSet(),
            "input contracts differ",
        )
    }

    // ── Funding leg (participant-managed sender, command service) ──────

    private suspend fun fundExternalParty(
        walletChannel: Channel,
        walletTokens: TokenStandardClient,
        registry: TransferRegistryClient,
        walletParty: String,
        to: String,
        amulet: InstrumentId,
    ) {
        val inputs = walletTokens.listHoldings(walletParty).filter { it.lock == null }
        assertTrue(inputs.isNotEmpty(), "operator wallet has no unlocked holdings")
        val transfer = Transfer(
            sender = walletParty,
            receiver = to,
            amount = BigDecimal("50.0"),
            instrumentId = amulet,
            requestedAt = Instant.now(),
            executeBefore = Instant.now().plusSeconds(24 * 3600),
            inputHoldingCids = inputs.map { it.contractId },
            meta = emptyMap(),
        )
        val factory = registry.transferFactory(
            ChoiceContextJson.transferFactoryChoiceArguments(amulet.admin, transfer)
        )
        val exercise = CommandsOuterClass.Command.newBuilder()
            .setExercise(
                CommandsOuterClass.ExerciseCommand.newBuilder()
                    .setTemplateId(TokenStandard.transferFactoryInterfaceId)
                    .setContractId(factory.factoryId)
                    .setChoice("TransferFactory_Transfer")
                    .setChoiceArgument(
                        DamlValues.record(
                            "expectedAdmin" to DamlValues.party(amulet.admin),
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
                        .setCommandId(UUID.randomUUID().toString())
                        .setUserId(walletUser)
                        .addActAs(walletParty)
                        .addCommands(exercise)
                        .addAllDisclosedContracts(
                            factory.choiceContext.disclosedContracts.map { it.toProto() }
                        )
                )
                .build()
        )
    }

    // ── Event plumbing (same rationale as DappSessionTest) ─────────────

    private fun CoroutineScope.collectingEvents(
        client: DappClient,
        into: MutableList<DappEvent>,
    ): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect { into += it }
    }

    private suspend fun awaitEvents(
        events: List<DappEvent>,
        satisfied: (List<DappEvent>) -> Boolean,
    ) {
        repeat(1000) {
            if (satisfied(events)) return
            kotlinx.coroutines.yield()
        }
        fail("expected events not observed; saw $events")
    }

    // ── LocalNet plumbing (house pattern: per-module test copies) ──────

    private suspend fun onboardWalletUser(validator: ValidatorClient): String {
        val status = runCatching { validator.userStatus() }.getOrNull()
        if (status != null && status.userOnboarded && status.partyId.isNotEmpty()) {
            return status.partyId
        }
        return retryUntil("wallet user onboarding") { validator.register() }
    }

    private suspend fun tap(validator: ValidatorClient, amountUsd: String): String =
        retryUntil("tap $amountUsd USD (waits for an open mining round)") {
            validator.tap(BigDecimal(amountUsd))
        }

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
        return ClientInterceptors.intercept(channel, interceptor)
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
