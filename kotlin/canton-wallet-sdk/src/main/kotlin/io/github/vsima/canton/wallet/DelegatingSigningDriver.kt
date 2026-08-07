// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CryptoOuterClass

/**
 * The custody hook: a [SigningDriver] whose key operations are delegated to
 * caller-supplied suspend functions. Wrap any external signer — Fireblocks,
 * BitGo, Dfns, an HSM, a remote co-signer — by adapting its SDK calls to the
 * two callbacks; everything above the driver (party onboarding, interactive
 * submission, token-standard writes) works unchanged.
 *
 * The callbacks own the encodings: [publicKeyProvider] must return a
 * Ledger-API-compatible key (see [CryptoOuterClass.SigningPublicKey]) and
 * [signer] a signature whose format/algorithm match that key. For P-256
 * custody backends that emit raw `r‖s` signatures, convert to DER before
 * returning ([CryptoOuterClass.SignatureFormat.SIGNATURE_FORMAT_DER]).
 */
public class DelegatingSigningDriver(
    private val publicKeyProvider: suspend () -> CryptoOuterClass.SigningPublicKey,
    private val signer: suspend (ByteArray) -> CryptoOuterClass.Signature,
) : SigningDriver {

    override suspend fun publicKey(): CryptoOuterClass.SigningPublicKey = publicKeyProvider()

    override suspend fun sign(bytes: ByteArray): CryptoOuterClass.Signature = signer(bytes)
}
