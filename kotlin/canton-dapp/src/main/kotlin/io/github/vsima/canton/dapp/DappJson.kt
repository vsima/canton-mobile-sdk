// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wire codec for the CIP-0103 types, written against OpenRPC 0.5.0.
 *
 * Hand-written over kotlinx-serialization's `JsonElement` API rather than
 * generated from `@Serializable`: no compiler plugin, no reflection, and
 * therefore nothing for R8 to strip on Android — the same choice
 * `:canton-wallet-sdk` made for the registry APIs.
 *
 * Encoding rule, applied throughout: **omit absent optionals, never emit
 * `null` for them.** A wallet that emits `"reason": null` is not wrong by
 * JSON-RPC, but round-tripping it changes the document, and the golden
 * vectors in `testdata/dapp/` assert that decode-then-encode is a fixpoint.
 * Decoding is the tolerant direction — an explicit `null` reads as absent.
 */
public object DappJson {

    // ── Provider / status ──────────────────────────────────────────────

    public fun encode(value: DappProvider): JsonObject = buildJsonObject {
        put("id", value.id)
        value.version?.let { put("version", it) }
        value.providerType?.let { put("providerType", it.wire) }
        value.url?.let { put("url", it) }
        value.userUrl?.let { put("userUrl", it) }
    }

    public fun decodeProvider(json: JsonElement): DappProvider {
        val obj = json.asObject("Provider")
        return DappProvider(
            id = obj.string("id"),
            version = obj.stringOrNull("version"),
            providerType = obj.stringOrNull("providerType")?.let {
                DappProviderType.fromWire(it)
                    ?: throw invalid("unknown providerType '$it'")
            },
            url = obj.stringOrNull("url"),
            userUrl = obj.stringOrNull("userUrl"),
        )
    }

    public fun encode(value: ConnectResult): JsonObject = buildJsonObject {
        put("isConnected", value.isConnected)
        value.reason?.let { put("reason", it) }
        put("isNetworkConnected", value.isNetworkConnected)
        value.networkReason?.let { put("networkReason", it) }
        value.userUrl?.let { put("userUrl", it) }
    }

    public fun decodeConnectResult(json: JsonElement): ConnectResult {
        val obj = json.asObject("ConnectResult")
        return ConnectResult(
            isConnected = obj.boolean("isConnected"),
            isNetworkConnected = obj.boolean("isNetworkConnected"),
            reason = obj.stringOrNull("reason"),
            networkReason = obj.stringOrNull("networkReason"),
            userUrl = obj.stringOrNull("userUrl"),
        )
    }

    public fun encode(value: DappNetwork): JsonObject = buildJsonObject {
        put("networkId", value.networkId)
        value.ledgerApi?.let { put("ledgerApi", it) }
        value.accessToken?.let { put("accessToken", it) }
    }

    public fun decodeNetwork(json: JsonElement): DappNetwork {
        val obj = json.asObject("Network")
        return DappNetwork(
            networkId = obj.string("networkId"),
            ledgerApi = obj.stringOrNull("ledgerApi"),
            accessToken = obj.stringOrNull("accessToken"),
        )
    }

    public fun encode(value: DappSessionInfo): JsonObject = buildJsonObject {
        put("accessToken", value.accessToken)
        put("userId", value.userId)
    }

    public fun decodeSessionInfo(json: JsonElement): DappSessionInfo {
        val obj = json.asObject("Session")
        return DappSessionInfo(
            accessToken = obj.string("accessToken"),
            userId = obj.string("userId"),
        )
    }

    public fun encode(value: DappStatus): JsonObject = buildJsonObject {
        put("provider", encode(value.provider))
        put("connection", encode(value.connection))
        value.network?.let { put("network", encode(it)) }
        value.session?.let { put("session", encode(it)) }
    }

    public fun decodeStatus(json: JsonElement): DappStatus {
        val obj = json.asObject("StatusEvent")
        return DappStatus(
            provider = decodeProvider(obj.required("provider")),
            connection = decodeConnectResult(obj.required("connection")),
            network = obj.optional("network")?.let { decodeNetwork(it) },
            session = obj.optional("session")?.let { decodeSessionInfo(it) },
        )
    }

    // ── Accounts ───────────────────────────────────────────────────────

    public fun encode(value: DappWallet): JsonObject = buildJsonObject {
        put("primary", value.primary)
        put("partyId", value.partyId)
        put("status", value.status.wire)
        put("hint", value.hint)
        put("publicKey", value.publicKey)
        put("namespace", value.namespace)
        put("networkId", value.networkId)
        put("signingProviderId", value.signingProviderId)
        value.externalTxId?.let { put("externalTxId", it) }
        value.topologyTransactions?.let { put("topologyTransactions", it) }
        value.disabled?.let { put("disabled", it) }
        value.reason?.let { put("reason", it) }
    }

    public fun decodeWallet(json: JsonElement): DappWallet {
        val obj = json.asObject("Wallet")
        val status = obj.string("status")
        return DappWallet(
            primary = obj.boolean("primary"),
            partyId = obj.string("partyId"),
            status = DappWalletStatus.fromWire(status)
                ?: throw invalid("unknown Wallet status '$status'"),
            hint = obj.string("hint"),
            publicKey = obj.string("publicKey"),
            namespace = obj.string("namespace"),
            networkId = obj.string("networkId"),
            signingProviderId = obj.string("signingProviderId"),
            externalTxId = obj.stringOrNull("externalTxId"),
            topologyTransactions = obj.stringOrNull("topologyTransactions"),
            disabled = obj.booleanOrNull("disabled"),
            reason = obj.stringOrNull("reason"),
        )
    }

    public fun encodeAccounts(value: List<DappWallet>): JsonArray =
        buildJsonArray { for (wallet in value) add(encode(wallet)) }

    public fun decodeAccounts(json: JsonElement): List<DappWallet> =
        json.asArray("ListAccountsResult").map { decodeWallet(it) }

    // ── signMessage ────────────────────────────────────────────────────

    public fun encode(value: SignMessageRequest): JsonObject = buildJsonObject {
        put("message", value.message)
    }

    public fun decodeSignMessageRequest(json: JsonElement): SignMessageRequest =
        SignMessageRequest(message = json.asObject("SignMessageRequest").string("message"))

    public fun encode(value: SignMessageResult): JsonObject = buildJsonObject {
        put("signature", value.signature)
    }

    public fun decodeSignMessageResult(json: JsonElement): SignMessageResult =
        SignMessageResult(signature = json.asObject("SignMessageResult").string("signature"))

    // ── ledgerApi ──────────────────────────────────────────────────────

    public fun encode(value: LedgerApiRequest): JsonObject = buildJsonObject {
        put("requestMethod", value.requestMethod.wire)
        put("resource", value.resource)
        value.body?.let { put("body", it) }
        value.query?.let { put("query", it) }
        value.path?.let { put("path", it) }
    }

    public fun decodeLedgerApiRequest(json: JsonElement): LedgerApiRequest {
        val obj = json.asObject("LedgerApiRequest")
        val method = obj.string("requestMethod")
        return LedgerApiRequest(
            requestMethod = LedgerApiMethod.fromWire(method)
                ?: throw invalid("unknown ledgerApi requestMethod '$method'"),
            resource = obj.string("resource"),
            body = obj.optional("body"),
            query = obj.optional("query")?.asObject("query"),
            path = obj.optional("path")?.asObject("path"),
        )
    }

    // ── prepareExecute ─────────────────────────────────────────────────

    public fun encode(value: PrepareSubmission): JsonObject = buildJsonObject {
        value.commandId?.let { put("commandId", it) }
        put("commands", value.commands)
        if (value.actAs.isNotEmpty()) put("actAs", value.actAs.toJsonArray())
        if (value.readAs.isNotEmpty()) put("readAs", value.readAs.toJsonArray())
        value.disclosedContracts?.let { put("disclosedContracts", it) }
        value.synchronizerId?.let { put("synchronizerId", it) }
        if (value.packageIdSelectionPreference.isNotEmpty()) {
            put("packageIdSelectionPreference", value.packageIdSelectionPreference.toJsonArray())
        }
    }

    public fun decodePrepareSubmission(json: JsonElement): PrepareSubmission {
        val obj = json.asObject("JsPrepareSubmissionRequest")
        return PrepareSubmission(
            commands = obj.required("commands").asArray("commands"),
            commandId = obj.stringOrNull("commandId"),
            actAs = obj.stringList("actAs"),
            readAs = obj.stringList("readAs"),
            disclosedContracts = obj.optional("disclosedContracts")?.asArray("disclosedContracts"),
            synchronizerId = obj.stringOrNull("synchronizerId"),
            packageIdSelectionPreference = obj.stringList("packageIdSelectionPreference"),
        )
    }

    public fun encode(value: PrepareSubmissionResult): JsonObject = buildJsonObject {
        value.preparedTransaction?.let { put("preparedTransaction", it) }
        value.preparedTransactionHash?.let { put("preparedTransactionHash", it) }
    }

    public fun decodePrepareSubmissionResult(json: JsonElement): PrepareSubmissionResult {
        val obj = json.asObject("JsPrepareSubmissionResponse")
        return PrepareSubmissionResult(
            preparedTransaction = obj.stringOrNull("preparedTransaction"),
            preparedTransactionHash = obj.stringOrNull("preparedTransactionHash"),
        )
    }

    /** `prepareExecuteAndWaitResult` — an executed `txChanged` under `tx`. */
    public fun encodeExecutedResult(value: TxChangedEvent.Executed): JsonObject = buildJsonObject {
        put("tx", encode(value))
    }

    public fun decodeExecutedResult(json: JsonElement): TxChangedEvent.Executed {
        val tx = decodeTxChanged(json.asObject("prepareExecuteAndWaitResult").required("tx"))
        return tx as? TxChangedEvent.Executed
            ?: throw invalid("prepareExecuteAndWait returned a '${tx.statusWire()}' tx, expected 'executed'")
    }

    // ── Events ─────────────────────────────────────────────────────────

    public fun encode(value: TxChangedEvent): JsonObject = buildJsonObject {
        put("status", value.statusWire())
        put("commandId", value.commandId)
        when (value) {
            is TxChangedEvent.Pending, is TxChangedEvent.Failed -> Unit
            is TxChangedEvent.Signed -> put(
                "payload",
                buildJsonObject {
                    put("signature", value.signature)
                    put("signedBy", value.signedBy)
                    put("party", value.party)
                },
            )
            is TxChangedEvent.Executed -> put(
                "payload",
                buildJsonObject {
                    put("updateId", value.updateId)
                    put("completionOffset", value.completionOffset)
                },
            )
        }
    }

    public fun decodeTxChanged(json: JsonElement): TxChangedEvent {
        val obj = json.asObject("TxChangedEvent")
        val commandId = obj.string("commandId")
        return when (val status = obj.string("status")) {
            "pending" -> TxChangedEvent.Pending(commandId)
            "failed" -> TxChangedEvent.Failed(commandId)
            "signed" -> {
                val payload = obj.required("payload").asObject("TxChangedSignedPayload")
                TxChangedEvent.Signed(
                    commandId = commandId,
                    signature = payload.string("signature"),
                    signedBy = payload.string("signedBy"),
                    party = payload.string("party"),
                )
            }
            "executed" -> {
                val payload = obj.required("payload").asObject("TxChangedExecutedPayload")
                TxChangedEvent.Executed(
                    commandId = commandId,
                    updateId = payload.string("updateId"),
                    completionOffset = payload.long("completionOffset"),
                )
            }
            else -> throw invalid("unknown txChanged status '$status'")
        }
    }

    public fun encode(value: MessageSignatureEvent): JsonObject = buildJsonObject {
        put("status", value.statusWire())
        put("messageId", value.messageId)
        if (value is MessageSignatureEvent.Signed) put("signature", value.signature)
    }

    public fun decodeMessageSignature(json: JsonElement): MessageSignatureEvent {
        val obj = json.asObject("MessageSignatureEvent")
        val messageId = obj.string("messageId")
        return when (val status = obj.string("status")) {
            "pending" -> MessageSignatureEvent.Pending(messageId)
            "failed" -> MessageSignatureEvent.Failed(messageId)
            "signed" -> MessageSignatureEvent.Signed(messageId, obj.string("signature"))
            else -> throw invalid("unknown messageSignature status '$status'")
        }
    }

    /**
     * A wallet-to-dApp event as a JSON-RPC notification. The event name is
     * the notification's `method`, and its payload is `params`.
     */
    public fun encodeEvent(event: DappEvent): JsonRpcRequest = when (event) {
        is DappEvent.AccountsChanged -> JsonRpcRequest(
            method = DappMethod.ACCOUNTS_CHANGED.wire,
            params = encodeAccounts(event.accounts),
        )
        is DappEvent.TxChanged -> JsonRpcRequest(
            method = DappMethod.TX_CHANGED.wire,
            params = encode(event.tx),
        )
        is DappEvent.MessageSignature -> JsonRpcRequest(
            method = DappMethod.MESSAGE_SIGNATURE.wire,
            params = encode(event.signature),
        )
    }

    /**
     * Decodes a notification into a [DappEvent], or null when [notification]
     * names a method that is not an event. Null rather than an exception:
     * an unknown notification is something to ignore, not something to fail
     * a session over.
     */
    public fun decodeEvent(notification: JsonRpcRequest): DappEvent? {
        val params = notification.params ?: return null
        return when (DappMethod.fromWire(notification.method)) {
            DappMethod.ACCOUNTS_CHANGED -> DappEvent.AccountsChanged(decodeAccounts(params))
            DappMethod.TX_CHANGED -> DappEvent.TxChanged(decodeTxChanged(params))
            DappMethod.MESSAGE_SIGNATURE -> DappEvent.MessageSignature(decodeMessageSignature(params))
            else -> null
        }
    }
}

// ── Wire spellings for the sealed lifecycles ───────────────────────────

internal fun TxChangedEvent.statusWire(): String = when (this) {
    is TxChangedEvent.Pending -> "pending"
    is TxChangedEvent.Signed -> "signed"
    is TxChangedEvent.Executed -> "executed"
    is TxChangedEvent.Failed -> "failed"
}

internal fun MessageSignatureEvent.statusWire(): String = when (this) {
    is MessageSignatureEvent.Pending -> "pending"
    is MessageSignatureEvent.Signed -> "signed"
    is MessageSignatureEvent.Failed -> "failed"
}

// ── Decoding helpers ───────────────────────────────────────────────────
//
// Every failure is INVALID_PARAMS carrying the field name: a dApp debugging
// a rejected call needs to know which field, and "expected object" alone
// has cost enough time elsewhere in this codebase.

internal fun invalid(message: String): DappException =
    DappException(DappErrorCode.INVALID_PARAMS, message)

private fun JsonElement.asObject(what: String): JsonObject =
    this as? JsonObject ?: throw invalid("$what must be a JSON object, was ${describe()}")

private fun JsonElement.asArray(what: String): JsonArray =
    this as? JsonArray ?: throw invalid("$what must be a JSON array, was ${describe()}")

private fun JsonElement.describe(): String = when (this) {
    is JsonNull -> "null"
    is JsonPrimitive -> if (isString) "a string" else "the primitive $content"
    is JsonArray -> "an array"
    is JsonObject -> "an object"
}

/** Present and not JSON null. */
private fun JsonObject.optional(name: String): JsonElement? =
    this[name]?.takeIf { it !is JsonNull }

private fun JsonObject.required(name: String): JsonElement =
    optional(name) ?: throw invalid("missing required field '$name'")

private fun JsonObject.primitive(name: String): JsonPrimitive? =
    optional(name) as? JsonPrimitive

private fun JsonObject.string(name: String): String =
    stringOrNull(name) ?: throw invalid("missing required string field '$name'")

private fun JsonObject.stringOrNull(name: String): String? = primitive(name)?.content

private fun JsonObject.boolean(name: String): Boolean =
    booleanOrNull(name) ?: throw invalid("missing required boolean field '$name'")

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    primitive(name)?.content?.toBooleanStrictOrNull()

private fun JsonObject.long(name: String): Long =
    primitive(name)?.content?.toLongOrNull()
        ?: throw invalid("field '$name' must be an integer")

private fun JsonObject.stringList(name: String): List<String> =
    (optional(name) as? JsonArray)?.map {
        (it as? JsonPrimitive)?.content ?: throw invalid("'$name' must contain only strings")
    } ?: emptyList()

private fun List<String>.toJsonArray(): JsonArray =
    buildJsonArray { for (item in this@toJsonArray) add(JsonPrimitive(item)) }
