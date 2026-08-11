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
         * The rules [ReadOnly] is built from, exposed so a host can
         * compose a wider policy without restating the read surface.
         */
        public val ReadOnlyRules: Array<Pair<LedgerApiMethod, String>> = arrayOf(
            LedgerApiMethod.GET to "/v2/version",
            LedgerApiMethod.GET to "/v2/state/",
            LedgerApiMethod.POST to "/v2/state/",
            LedgerApiMethod.GET to "/v2/updates/",
            LedgerApiMethod.POST to "/v2/updates",
            LedgerApiMethod.POST to "/v2/events/events-by-contract-id",
            LedgerApiMethod.GET to "/v2/packages",
            LedgerApiMethod.GET to "/v2/interactive-submission/preferred-package-version",
            LedgerApiMethod.POST to "/v2/interactive-submission/preferred-packages",
        )

        /**
         * The read surface of the JSON Ledger API — an **allowlist of
         * method + path prefix**, not a rule about HTTP verbs.
         *
         * That distinction is the whole point. An earlier version of this
         * policy allowed `GET` and nothing else, on the usual HTTP
         * convention that `GET` is the safe verb. Canton's JSON Ledger API
         * does not follow that convention: the ACS query is
         * `POST /v2/state/active-contracts`, update reads are
         * `POST /v2/updates…`, and event lookup is
         * `POST /v2/events/events-by-contract-id`. Under a GET-only rule a
         * dApp could read the ledger end and the synchronizer list and
         * essentially nothing else — including, fatally, not its own
         * holdings, which a token-standard dApp needs to choose input UTXOs.
         *
         * Meanwhile `GET` is not reliably safe either: `POST /v2/packages`
         * uploads a DAR, so a verb-shaped rule gets the risk backwards in
         * both directions.
         *
         * What stays denied, by simply not being listed: command submission
         * and interactive submission (`prepare`/`execute` — a dApp reaches
         * those through `prepareExecute`, where they are approved and
         * hash-verified), DAR upload, package vetting, and every
         * user/party/identity-provider surface, which can grant rights and
         * allocate parties.
         */
        public val ReadOnly: LedgerApiPolicy = allowing(*ReadOnlyRules)

        /** Refuses everything. The right default for a wallet that has not thought about it. */
        public val DenyAll: LedgerApiPolicy = LedgerApiPolicy { false }

        /** Allows everything. For tests and for hosts that have made the call. */
        public val AllowAll: LedgerApiPolicy = LedgerApiPolicy { true }

        /**
         * A policy allowing exactly these `method to path-prefix` pairs, for
         * hosts widening [ReadOnly] deliberately.
         *
         * ```kotlin
         * val policy = LedgerApiPolicy.allowing(
         *     *LedgerApiPolicy.ReadOnlyRules,
         *     LedgerApiMethod.POST to "/v2/commands/async/submit",
         * )
         * ```
         *
         * Matching drops the query string and refuses any resource that is
         * not already canonical — see [canonicalLedgerApiPath] for why a
         * prefix cannot be escaped by traversal or percent-encoding.
         */
        public fun allowing(vararg allowed: Pair<LedgerApiMethod, String>): LedgerApiPolicy {
            val rules = allowed.map { (method, prefix) -> method to prefix.lowercase() }
            return LedgerApiPolicy { request ->
                val path = canonicalLedgerApiPath(request.resource)?.lowercase()
                path != null && rules.any { (method, prefix) ->
                    request.requestMethod == method && path.startsWith(prefix)
                }
            }
        }

    }
}

/**
 * The path a [LedgerApiPolicy] decision applies to, or null when the resource
 * is not in canonical form.
 *
 * **Refused rather than normalised, deliberately.** The policy and the URL
 * builder are two different parsers looking at the same string, and any
 * disagreement between them is a bypass. Verified: OkHttp percent-decodes
 * `%2e` and *then* resolves dot segments, so `/v2/state/%2e%2e/users` passes
 * a prefix check on `/v2/state/` and arrives at the server as `/v2/users` —
 * the administrative surface the policy exists to block.
 *
 * Normalising here instead would mean reimplementing OkHttp's and
 * Foundation's canonicalisation exactly, and staying identical to both as
 * they change. Accepting exactly one spelling makes the two parsers agree by
 * construction. Nothing legitimate is lost: Ledger API resources are plain
 * paths, and encoded *values* travel in [LedgerApiRequest.query], which the
 * URL builder escapes properly.
 */
internal fun canonicalLedgerApiPath(resource: String): String? {
    val path = resource.substringBefore('?').substringBefore('#')
    if (path.isEmpty()) return null
    // Percent-encoding and backslashes are the two ways a path can mean
    // something different to a parser than it reads as.
    if ('%' in path || '\\' in path) return null
    val normalised = if (path.startsWith("/")) path else "/$path"
    if (normalised.split('/').any { it == "." || it == ".." }) return null
    return normalised
}
