// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.LedgerApiMethod
import io.github.vsima.canton.dapp.LedgerApiRequest
import io.github.vsima.canton.dapp.PrepareSubmission
import io.github.vsima.canton.dapp.TxChangedEvent
import kotlinx.serialization.json.JsonElement

/**
 * The three collaborators [DappSession] needs to do anything that touches a
 * key or the ledger.
 *
 * They are separate interfaces rather than one because they fail
 * independently and are wanted independently: a wallet may proxy `ledgerApi`
 * reads without implementing `signMessage`, and a read-only companion app may
 * implement neither. Each one absent means that method answers `4200`, which
 * is the honest reply — the wallet genuinely does not support it.
 */

/** Signs an arbitrary message on behalf of an approved account. */
public fun interface DappMessageSigner {
    /**
     * Returns the signature, encoded however the wallet and its verifiers
     * agree — this SDK does not impose a format because CIP-0103 does not.
     *
     * Called only after the user has approved. Throw to fail the request;
     * [DappSession] converts the failure into a `messageSignature failed`
     * event and an error response.
     */
    public suspend fun sign(account: DappWallet, message: String): String
}

/**
 * Everything the prepare→verify→sign→execute pipeline needs for one
 * submission.
 *
 * [actAs] is the account [DappSession] resolved and the user approved — not
 * whatever the dApp put in [PrepareSubmission.actAs]. Implementations must
 * build the ledger envelope from this field, which is the point at which a
 * dApp is prevented from acting as a party it merely named.
 */
public data class PrepareExecuteContext(
    val commandId: String,
    val actAs: DappWallet,
    val submission: PrepareSubmission,
    val network: DappNetworkConfig,
    /**
     * Publishes intermediate lifecycle events — in practice the `signed`
     * step, between approval and execution. `pending` and the terminal
     * states are emitted by [DappSession] itself, so an implementation that
     * ignores this still produces a correct event stream, just a coarser one.
     */
    val emitEvent: suspend (TxChangedEvent) -> Unit = {},
)

/**
 * Prepares, verifies, signs and executes one submission.
 *
 * The contract that matters: an implementation **must** recompute the
 * transaction hash from the prepared bytes the participant returned and
 * refuse to sign on a mismatch. A wallet signs only what it verified, and
 * this interface is the only place that check can live.
 */
public fun interface PrepareExecutePipeline {
    public suspend fun execute(context: PrepareExecuteContext): TxChangedEvent.Executed
}

/** Performs an authenticated call against the JSON Ledger API. */
public fun interface LedgerApiProxy {
    public suspend fun call(request: LedgerApiRequest): JsonElement
}

/**
 * Which `ledgerApi` resources a dApp may reach through the wallet.
 *
 * `ledgerApi` is the wallet acting as an authenticating proxy, so without a
 * policy it is an open door onto the ledger with the wallet's own
 * credentials. The default is deliberately narrow; hosts widen it
 * deliberately.
 */
public fun interface LedgerApiPolicy {
    public fun allows(request: LedgerApiRequest): Boolean

    public companion object {
        /**
         * Read-style resources only.
         *
         * `GET` alone, and never the administrative surfaces: user
         * management and party management can grant rights and allocate
         * parties, and neither is something a dApp should reach *through*
         * a wallet even when the wallet itself may.
         */
        public val ReadOnly: LedgerApiPolicy = LedgerApiPolicy { request ->
            request.requestMethod == LedgerApiMethod.GET && !request.resource.isAdministrative()
        }

        /** Refuses everything. The right default for a wallet that has not thought about it. */
        public val DenyAll: LedgerApiPolicy = LedgerApiPolicy { false }

        /** Allows everything. For tests and for hosts that have made the call. */
        public val AllowAll: LedgerApiPolicy = LedgerApiPolicy { true }

        private val ADMINISTRATIVE = listOf("/users", "/parties", "/idps", "/identity-provider")

        private fun String.isAdministrative(): Boolean {
            val path = substringBefore('?').trimEnd('/').lowercase()
            return ADMINISTRATIVE.any { path.endsWith(it) || path.contains("$it/") }
        }
    }
}
