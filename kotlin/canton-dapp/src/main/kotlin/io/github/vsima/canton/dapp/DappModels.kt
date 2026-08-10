// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The CIP-0103 dApp API types, mirroring the OpenRPC document
 * `hyperledger-labs/splice-wallet-kernel` `/api-specs/openrpc-dapp-api.json`
 * at **`info.version` 0.5.0**.
 *
 * Field names, optionality and enum spellings are taken from that document
 * rather than from prose: several secondary specs circulating around this
 * protocol invent method names (`canton_connect`, `canton_getAccounts`) and
 * fields that do not exist. When the OpenRPC and any other source disagree,
 * the OpenRPC wins — and [DappMethod] is the exhaustive list of what exists.
 */

/** The wallet, as advertised to a dApp. OpenRPC `Provider`. */
public data class DappProvider(
    val id: String,
    val version: String? = null,
    val providerType: DappProviderType? = null,
    val url: String? = null,
    /**
     * Where a *remote* wallet kernel wants the user sent to complete an
     * action out of band. Native wallets on this SDK leave it null: the
     * approval happens in the wallet app itself.
     */
    val userUrl: String? = null,
)

public enum class DappProviderType(public val wire: String) {
    BROWSER("browser"),
    DESKTOP("desktop"),
    MOBILE("mobile"),
    REMOTE("remote"),
    ;

    public companion object {
        public fun fromWire(value: String): DappProviderType? = entries.find { it.wire == value }
    }
}

/**
 * Result of [DappMethod.CONNECT] and [DappMethod.IS_CONNECTED].
 *
 * Two independent booleans, and they are genuinely independent: a dApp can
 * be connected to the *wallet* while the wallet is not connected to a
 * *network*. Report both honestly — collapsing them into one flag is what
 * produces "connected" UIs that cannot submit anything.
 */
public data class ConnectResult(
    val isConnected: Boolean,
    val isNetworkConnected: Boolean,
    val reason: String? = null,
    val networkReason: String? = null,
    val userUrl: String? = null,
)

/** OpenRPC `Network`. [networkId] is a CAIP-2 identifier, e.g. `canton:da-mainnet`. */
public data class DappNetwork(
    val networkId: String,
    val ledgerApi: String? = null,
    /**
     * Present in the schema, but this SDK never populates it — see
     * `DappSession`. Handing a dApp a ledger token would let it bypass the
     * wallet entirely.
     */
    val accessToken: String? = null,
)

/**
 * OpenRPC `Session`. Named `…Info` here because `DappSession` is the
 * wallet-side engine in `:canton-dapp-wallet`, and one of the two had to
 * give way.
 */
public data class DappSessionInfo(
    val accessToken: String,
    val userId: String,
)

/**
 * One account. OpenRPC calls this `Wallet`, which is confusing in a codebase
 * where "wallet" is the application — but the wire name is what it is, and
 * renaming the type would not rename the JSON.
 */
public data class DappWallet(
    val primary: Boolean,
    val partyId: String,
    val status: DappWalletStatus,
    val hint: String,
    val publicKey: String,
    val namespace: String,
    val networkId: String,
    val signingProviderId: String,
    val externalTxId: String? = null,
    val topologyTransactions: String? = null,
    val disabled: Boolean? = null,
    val reason: String? = null,
)

public enum class DappWalletStatus(public val wire: String) {
    INITIALIZED("initialized"),
    ALLOCATED("allocated"),
    REMOVED("removed"),
    ;

    public companion object {
        public fun fromWire(value: String): DappWalletStatus? = entries.find { it.wire == value }
    }
}

/** Result of [DappMethod.STATUS]. OpenRPC `StatusEvent`. */
public data class DappStatus(
    val provider: DappProvider,
    val connection: ConnectResult,
    val network: DappNetwork? = null,
    val session: DappSessionInfo? = null,
)

/** Params of [DappMethod.SIGN_MESSAGE]. */
public data class SignMessageRequest(val message: String)

/** Result of [DappMethod.SIGN_MESSAGE]. */
public data class SignMessageResult(val signature: String)

/**
 * Params of [DappMethod.LEDGER_API] — the wallet acting as an
 * authenticating proxy onto the JSON Ledger API.
 *
 * Note there is **no `headers` field** in OpenRPC 0.5.0. That is a feature,
 * not an omission to work around: headers are how a caller would smuggle its
 * own `Authorization`, and the whole point of proxying is that the wallet
 * supplies that.
 */
public data class LedgerApiRequest(
    val requestMethod: LedgerApiMethod,
    val resource: String,
    val body: JsonElement? = null,
    val query: JsonObject? = null,
    val path: JsonObject? = null,
)

public enum class LedgerApiMethod(public val wire: String) {
    GET("get"),
    POST("post"),
    PATCH("patch"),
    PUT("put"),
    DELETE("delete"),
    ;

    public companion object {
        public fun fromWire(value: String): LedgerApiMethod? = entries.find { it.wire == value }
    }
}

/**
 * Params of [DappMethod.PREPARE_EXECUTE] and
 * [DappMethod.PREPARE_EXECUTE_AND_WAIT]. OpenRPC
 * `JsPrepareSubmissionRequest`.
 *
 * [commands] are **JSON Ledger API** command shapes (`CreateCommand`,
 * `ExerciseCommand`, `CreateAndExerciseCommand`, `ExerciseByKeyCommand`),
 * carried as raw JSON. They are deliberately not modelled further: the
 * wallet proxies them to the participant unchanged, and every Daml value
 * shape re-encoded here would be a place for the transaction the user
 * approved to drift from the one that gets signed.
 *
 * The envelope fields are a different matter. A dApp may express a
 * preference, but the wallet decides — see `DappSession`, which overrides
 * [actAs] with the party the user actually approved. A dApp that could set
 * `actAs` could make the wallet act as any party it names.
 */
public data class PrepareSubmission(
    val commands: JsonArray,
    val commandId: String? = null,
    val actAs: List<String> = emptyList(),
    val readAs: List<String> = emptyList(),
    val disclosedContracts: JsonArray? = null,
    val synchronizerId: String? = null,
    val packageIdSelectionPreference: List<String> = emptyList(),
)

/**
 * OpenRPC `JsPrepareSubmissionResponse`. Both fields are optional in the
 * CIP and both are strings — [preparedTransaction] is base64 of the
 * serialized `PreparedTransaction` protobuf, which is what lets a wallet
 * recompute the hash over the exact bytes the participant produced.
 */
public data class PrepareSubmissionResult(
    val preparedTransaction: String? = null,
    val preparedTransactionHash: String? = null,
)

/**
 * Lifecycle of one submission, as delivered by the `txChanged` event and
 * returned (in its `executed` form) by `prepareExecuteAndWait`.
 *
 * `pending` → `signed` → `executed`, or `failed` from anywhere. Modelled as
 * a sealed hierarchy because the payload differs per state and only
 * `executed` carries an update id.
 */
public sealed interface TxChangedEvent {
    public val commandId: String

    public data class Pending(override val commandId: String) : TxChangedEvent

    public data class Signed(
        override val commandId: String,
        val signature: String,
        val signedBy: String,
        val party: String,
    ) : TxChangedEvent

    public data class Executed(
        override val commandId: String,
        val updateId: String,
        val completionOffset: Long,
    ) : TxChangedEvent

    public data class Failed(override val commandId: String) : TxChangedEvent
}

/** Lifecycle of one `signMessage` request, as delivered by `messageSignature`. */
public sealed interface MessageSignatureEvent {
    public val messageId: String

    public data class Pending(override val messageId: String) : MessageSignatureEvent

    public data class Signed(
        override val messageId: String,
        val signature: String,
    ) : MessageSignatureEvent

    public data class Failed(override val messageId: String) : MessageSignatureEvent
}

/**
 * An event pushed from wallet to dApp, as a JSON-RPC notification.
 *
 * The set is exactly the three event methods in OpenRPC 0.5.0. Note there is
 * **no `statusChanged`** — it appears in some prose descriptions of this
 * protocol but not in the document, which was checked. Status is polled via
 * [DappMethod.STATUS].
 */
public sealed interface DappEvent {
    public data class AccountsChanged(val accounts: List<DappWallet>) : DappEvent

    public data class TxChanged(val tx: TxChangedEvent) : DappEvent

    public data class MessageSignature(val signature: MessageSignatureEvent) : DappEvent
}

/**
 * Every method in OpenRPC 0.5.0, request and event alike.
 *
 * The event methods are listed here too because on the wire they are
 * ordinary JSON-RPC method names — they simply travel as notifications
 * (no `id`) in the wallet-to-dApp direction.
 */
public enum class DappMethod(public val wire: String) {
    STATUS("status"),
    CONNECT("connect"),
    DISCONNECT("disconnect"),
    IS_CONNECTED("isConnected"),
    GET_ACTIVE_NETWORK("getActiveNetwork"),
    LIST_ACCOUNTS("listAccounts"),
    GET_PRIMARY_ACCOUNT("getPrimaryAccount"),
    SIGN_MESSAGE("signMessage"),
    PREPARE_EXECUTE("prepareExecute"),
    PREPARE_EXECUTE_AND_WAIT("prepareExecuteAndWait"),
    LEDGER_API("ledgerApi"),

    ACCOUNTS_CHANGED("accountsChanged"),
    TX_CHANGED("txChanged"),
    MESSAGE_SIGNATURE("messageSignature"),
    ;

    public companion object {
        private val byWire: Map<String, DappMethod> = entries.associateBy { it.wire }

        public fun fromWire(value: String): DappMethod? = byWire[value]
    }
}
