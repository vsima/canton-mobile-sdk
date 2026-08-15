// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.DappNetwork
import io.github.vsima.canton.dapp.DappProvider
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.PrepareSubmission

/**
 * Who is asking. Built by the transport from what it can actually attest —
 * a WalletConnect session's peer metadata, a LAN pairing's certificate, the
 * host app itself for the in-process case.
 *
 * **Never construct this from a request payload.** A peer that names itself
 * in its own request is a peer that can name itself anything, and this
 * struct is what gets rendered on the approval sheet.
 */
public data class DappPeer(
    val id: String,
    val name: String,
    val url: String? = null,
    val iconUrl: String? = null,
    /**
     * Whether the transport could verify [name]/[url] cryptographically or
     * out of band. False means the UI must say so: an unverified peer name
     * is a claim, not an identity.
     */
    val verified: Boolean = false,
)

/** What the user is being asked to approve. */
public sealed interface DappApprovalRequest {
    public val peer: DappPeer

    /**
     * Connect, and share accounts. [available] is what the wallet could
     * offer; the user chooses a subset.
     */
    public data class Connection(
        override val peer: DappPeer,
        val network: DappNetwork,
        val available: List<DappWallet>,
    ) : DappApprovalRequest

    /**
     * Sign and submit a transaction.
     *
     * [submission] is what the dApp asked for — its `commands`, plus any
     * `readAs`/`disclosedContracts`. It is the dApp-authored *intent*, and it
     * is the only description of the transaction the engine hands this request:
     * the compiled prepared transaction does not exist yet at approval time.
     * The wallet still supplies the envelope — [actAs], `commandId`,
     * `synchronizerId` — and has validated `actAs`/`readAs` against this peer's
     * grant before you are asked.
     *
     * If the wallet must show the user the exact effects that will be signed
     * (not just the dApp's intent), it should decode and render
     * `prepared.preparedTransaction` from the prepare step itself. The pipeline
     * recomputes and verifies that transaction's hash before signing, but the
     * engine does not render it for you.
     */
    public data class Transaction(
        override val peer: DappPeer,
        val actAs: DappWallet,
        val network: DappNetwork,
        val submission: PrepareSubmission,
    ) : DappApprovalRequest

    /** Sign an arbitrary message with the account's key. */
    public data class Message(
        override val peer: DappPeer,
        val signWith: DappWallet,
        val message: String,
    ) : DappApprovalRequest
}

/** The user's answer. Anything other than [Approved] becomes a `4001`. */
public sealed interface DappApproval {
    /**
     * Approved. For a [DappApprovalRequest.Connection], [accounts] is the
     * subset the user chose to share — an empty list is a rejection, not an
     * approval of nothing.
     */
    public data class Approved(val accounts: List<DappWallet> = emptyList()) : DappApproval

    public data class Rejected(val reason: String = "User rejected the request") : DappApproval
}

/**
 * The wallet UI, from the engine's point of view.
 *
 * Implementations suspend until the user answers. There is deliberately no
 * timeout and no auto-approve: an implementation that returns [DappApproval.Approved]
 * without asking turns the wallet into a custodial signer for whoever holds
 * the transport, which is a different product with a different security
 * review.
 */
public fun interface DappApprovalDelegate {
    public suspend fun approve(request: DappApprovalRequest): DappApproval
}

/**
 * The accounts a wallet could offer a dApp.
 *
 * Separate from `WalletStore` on purpose: the store holds signing identities
 * in the wallet's own terms, while this returns the CIP-0103 projection of
 * them. A host maps between the two and decides what is eligible to share at
 * all.
 */
public fun interface DappAccountsSource {
    public suspend fun accounts(): List<DappWallet>
}

/** The network a session operates on, and how to reach its JSON Ledger API. */
public data class DappNetworkConfig(
    /** CAIP-2 network id, e.g. `canton:da-mainnet`. */
    val networkId: String,
    /**
     * Base URL of the JSON Ledger API, e.g. `http://127.0.0.1:2975`.
     *
     * Configured, never hard-coded: LocalNet's 2975/3975/4975 are a compose
     * port mapping, not a Canton default, and a real deployment puts it
     * wherever the operator chose.
     */
    val jsonApiBaseUrl: String? = null,
    /** The synchronizer the wallet submits to. Supplied by the wallet, never by a dApp. */
    val synchronizerId: String? = null,
    /** Mints a ledger access token. Its value never reaches a dApp. */
    val accessTokenProvider: (suspend () -> String)? = null,
) {
    /**
     * The dApp-visible view. [DappNetwork.accessToken] is deliberately
     * absent: handing a dApp a ledger token would let it act without the
     * wallet, which defeats every approval in this file.
     */
    public fun toDappNetwork(): DappNetwork =
        DappNetwork(networkId = networkId, ledgerApi = jsonApiBaseUrl, accessToken = null)
}
