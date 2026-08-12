// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

/**
 * The exact bytes a `signMessage` request signs — and the reason they are not
 * simply the message's bytes.
 *
 * A wallet's account key also signs transaction hashes (the prepared
 * transaction the ledger asks the party to authorize). If `signMessage` signed
 * a caller-supplied string raw, a hostile dApp could hand the user a "message"
 * whose bytes are a transaction hash it wants authorized, and a signature over
 * it would be a valid ledger signature. Domain separation prevents that: every
 * `signMessage` signature is over a fixed prefix followed by the message, so it
 * lives in a byte space that can never overlap a transaction hash.
 *
 * The separation is **structural, not merely conventional**: [DOMAIN] is longer
 * than 32 bytes, so [signingBytes] is always longer than 32 bytes, while a
 * prepared transaction hash is exactly 32. For Ed25519 — which signs the bytes
 * directly — the two can therefore never be equal for *any* message. For
 * ECDSA, which signs `SHA256(bytes)`, the domain sits inside the pre-image, so
 * a collision would require finding a message whose digest equals a chosen
 * transaction hash.
 *
 * This lives in `canton-dapp`, with no crypto, on purpose: **both** ends need
 * it. The wallet applies it before signing; a dApp verifying a signature (a
 * sign-in flow checking a signed nonce, say) must apply the identical transform
 * before verifying, or the signature will not validate. It is the shared
 * contract, so it is in the shared, dependency-light module.
 */
public object DappSignMessage {

    /**
     * The domain-separation prefix. Versioned so the scheme can change without
     * a silent signature-meaning shift, and deliberately longer than a 32-byte
     * transaction hash (38 bytes) so [signingBytes] can never be one.
     *
     * **Interop-critical: this constant is byte-for-byte identical across the
     * Kotlin and Swift SDKs.** A shared golden vector (`testdata/dapp/
     * signmessage.json`) pins it so the two cannot drift; a wallet on one
     * platform and a dApp on the other must agree on it exactly.
     */
    public const val DOMAIN: String = "CantonNetwork:CIP-0103:signMessage:v1\n"

    /** The bytes actually signed for [message]: [DOMAIN] then the UTF-8 message. */
    public fun signingBytes(message: String): ByteArray =
        (DOMAIN + message).encodeToByteArray()
}
