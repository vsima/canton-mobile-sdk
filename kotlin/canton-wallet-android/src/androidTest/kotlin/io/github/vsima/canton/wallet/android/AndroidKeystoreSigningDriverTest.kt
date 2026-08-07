// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daml.ledger.api.v2.CryptoOuterClass
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the Android Keystore driver — the Android half
 * of the "device-held keys" claim. Runs on any device or emulator;
 * hardware-backing is asserted only where the platform reports it, and the
 * achieved security level is printed either way.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSigningDriverTest {

    private fun verify(
        publicKey: CryptoOuterClass.SigningPublicKey,
        signature: CryptoOuterClass.Signature,
        signed: ByteArray,
    ): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicKey.keyData.toByteArray()))
        )
        update(signed)
        verify(signature.signature.toByteArray())
    }

    @Test
    fun keystoreKeySignsWithCantonEncodings() = runBlocking {
        val alias = "canton-test-${UUID.randomUUID()}"
        try {
            val driver = AndroidKeystoreSigningDriver.generate(alias)
            println("KEYSTORE-CHECK: securityLevel=${driver.securityLevel}")
            assertTrue(
                "expected hardware-backed keystore on a physical device",
                driver.isHardwareBacked,
            )

            val hash = ByteArray(32) { it.toByte() }
            val publicKey = driver.publicKey()
            val signature = driver.sign(hash)

            assertEquals(
                CryptoOuterClass.SigningKeySpec.SIGNING_KEY_SPEC_EC_P256,
                publicKey.keySpec,
            )
            assertEquals(
                CryptoOuterClass.CryptoKeyFormat.CRYPTO_KEY_FORMAT_DER_X509_SUBJECT_PUBLIC_KEY_INFO,
                publicKey.format,
            )
            assertEquals(CryptoOuterClass.SignatureFormat.SIGNATURE_FORMAT_DER, signature.format)
            assertEquals(
                CryptoOuterClass.SigningAlgorithmSpec.SIGNING_ALGORITHM_SPEC_EC_DSA_SHA_256,
                signature.signingAlgorithmSpec,
            )
            assertTrue("signature must verify", verify(publicKey, signature, hash))
            println("KEYSTORE-CHECK: PASS keystore key signs; P-256/DER/ECDSA-SHA256 verified")
        } finally {
            AndroidKeystoreSigningDriver.delete(alias)
        }
    }

    @Test
    fun keystoreKeyReloadsByAliasAndStillSigns() = runBlocking {
        val alias = "canton-test-${UUID.randomUUID()}"
        try {
            val original = AndroidKeystoreSigningDriver.generate(alias)
            val originalKey = original.publicKey()

            val reloaded = AndroidKeystoreSigningDriver.load(alias)
            assertNotNull("key must reload by alias", reloaded)
            assertEquals(originalKey.keyData, reloaded!!.publicKey().keyData)

            val hash = ByteArray(32) { (it * 3).toByte() }
            assertTrue(verify(originalKey, reloaded.sign(hash), hash))
            println("KEYSTORE-CHECK: PASS key reloads by alias and still signs")
        } finally {
            AndroidKeystoreSigningDriver.delete(alias)
        }
    }

    @Test
    fun deletedAliasStopsLoading() {
        val alias = "canton-test-${UUID.randomUUID()}"
        AndroidKeystoreSigningDriver.generate(alias)
        AndroidKeystoreSigningDriver.delete(alias)
        assertEquals(null, AndroidKeystoreSigningDriver.load(alias))
    }

    /**
     * The StrongBox branch, on devices that ship the StrongBox HAL: a key
     * generated with `requireStrongBox = true` must land in the dedicated
     * secure element — no silent TEE fallback — and still sign with
     * Canton's encodings. Skipped where the feature flag is absent.
     */
    @Test
    fun strongBoxKeyLandsInTheSecureElementWhereSupported() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(
            "device does not declare android.hardware.strongbox_keystore; skipping",
            context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE
            ),
        )

        val alias = "canton-test-sb-${UUID.randomUUID()}"
        try {
            val driver = AndroidKeystoreSigningDriver.generate(alias, requireStrongBox = true)
            println("KEYSTORE-CHECK: strongbox securityLevel=${driver.securityLevel}")
            assertEquals(
                AndroidKeystoreSigningDriver.SecurityLevel.STRONGBOX,
                driver.securityLevel,
            )

            val hash = ByteArray(32) { (it * 7).toByte() }
            assertTrue(verify(driver.publicKey(), driver.sign(hash), hash))
            println("KEYSTORE-CHECK: PASS StrongBox-resident key signs; encodings verified")
        } finally {
            AndroidKeystoreSigningDriver.delete(alias)
        }
    }
}
