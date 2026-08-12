// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wallet

import io.github.vsima.canton.dapp.DappSignMessage
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.DappWalletStatus
import io.github.vsima.canton.wallet.SoftwareSigningDriver
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The R4 reality check: a `signMessage` signature must be *verifiable by a
 * dApp* — that is the whole point of a sign-in flow — and it must be over the
 * domain-separated bytes, not the raw message.
 *
 * So this does not assert on the signer's internals. It signs through the
 * public [SigningDriverMessageSigner], then verifies the result exactly as an
 * independent dApp would: reconstruct the account's public key, verify the
 * signature over [DappSignMessage.signingBytes]. That it validates proves the
 * scheme is usable; that the *raw* message does **not** validate proves the
 * domain separation is real rather than decorative.
 */
class SigningDriverMessageSignerTest {

    private val account = DappWallet(
        primary = true,
        partyId = "alice::1220aa",
        status = DappWalletStatus.ALLOCATED,
        hint = "alice",
        publicKey = "",
        namespace = "1220aa",
        networkId = "canton:localnet",
        signingProviderId = "software",
    )

    private val message = "Sign in to Example dApp\nnonce: 7f3a9c2e"

    @Test
    fun `an Ed25519 signMessage signature verifies over the domain-separated bytes`() =
        runBlocking { roundTrip(SoftwareSigningDriver.Algorithm.ED25519, "Ed25519", "Ed25519") }

    @Test
    fun `a P-256 signMessage signature verifies over the domain-separated bytes`() =
        runBlocking { roundTrip(SoftwareSigningDriver.Algorithm.EC_P256, "EC", "SHA256withECDSA") }

    private suspend fun roundTrip(
        algorithm: SoftwareSigningDriver.Algorithm,
        keyFactory: String,
        signatureAlgorithm: String,
    ) {
        val driver = SoftwareSigningDriver.generate(algorithm)
        val signer = SigningDriverMessageSigner(driver)

        val signatureB64 = signer.sign(account, message)
        val signature = Base64.getDecoder().decode(signatureB64)

        // Exactly what a dApp holds: the account's public key, as the
        // X.509 SubjectPublicKeyInfo DER the driver publishes.
        val spki = driver.publicKey().keyData.toByteArray()
        val publicKey = KeyFactory.getInstance(keyFactory).generatePublic(X509EncodedKeySpec(spki))

        fun verifies(bytes: ByteArray): Boolean =
            Signature.getInstance(signatureAlgorithm).run {
                initVerify(publicKey)
                update(bytes)
                verify(signature)
            }

        // A dApp applying the same transform validates the signature.
        assertTrue(
            verifies(DappSignMessage.signingBytes(message)),
            "$algorithm: a dApp must be able to verify the signature over the signing bytes",
        )
        // The wallet did not sign the raw message — domain separation is real.
        assertFalse(
            verifies(message.toByteArray(Charsets.UTF_8)),
            "$algorithm: the raw message must NOT verify, or the separation is decorative",
        )
    }
}
