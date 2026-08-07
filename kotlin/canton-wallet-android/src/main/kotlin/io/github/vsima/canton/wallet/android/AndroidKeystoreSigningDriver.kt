// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet.android

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.daml.ledger.api.v2.CryptoOuterClass
import com.google.protobuf.ByteString
import io.github.vsima.canton.wallet.SigningDriver
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * P-256 keys generated inside the Android Keystore: the private key is
 * non-exportable and every signature is produced by the secure hardware.
 *
 * Tries StrongBox (a dedicated tamper-resistant element, API 28+) first and
 * falls back to the TEE-backed keystore — the path most devices take, since
 * many capable SoCs ship without the StrongBox HAL enabled. [securityLevel]
 * reports where the key actually landed; render it honestly rather than
 * claiming StrongBox everywhere. Pass `requireStrongBox = true` to fail
 * instead of falling back.
 *
 * Keys are addressed by [alias] and live in the keystore across launches —
 * store the alias (e.g. as a [io.github.vsima.canton.wallet.WalletRecord]
 * key handle) and [load] it on the next start.
 */
public class AndroidKeystoreSigningDriver private constructor(
    public val alias: String,
    private val privateKey: PrivateKey,
    private val publicKeyDer: ByteArray,
    public val securityLevel: SecurityLevel,
) : SigningDriver {

    public enum class SecurityLevel {
        /** Dedicated tamper-resistant secure element. */
        STRONGBOX,

        /** Hardware-backed keystore in the TEE (TrustZone). */
        TRUSTED_ENVIRONMENT,

        /** Software keystore — no hardware protection. */
        SOFTWARE,

        /** The platform would not say (old API level and no KeyInfo). */
        UNKNOWN,
    }

    /** True when the key cannot leave secure hardware. */
    public val isHardwareBacked: Boolean
        get() = securityLevel == SecurityLevel.STRONGBOX ||
            securityLevel == SecurityLevel.TRUSTED_ENVIRONMENT

    override suspend fun publicKey(): CryptoOuterClass.SigningPublicKey =
        CryptoOuterClass.SigningPublicKey.newBuilder()
            .setFormat(CryptoOuterClass.CryptoKeyFormat.CRYPTO_KEY_FORMAT_DER_X509_SUBJECT_PUBLIC_KEY_INFO)
            .setKeyData(ByteString.copyFrom(publicKeyDer))
            .setKeySpec(CryptoOuterClass.SigningKeySpec.SIGNING_KEY_SPEC_EC_P256)
            .build()

    override suspend fun sign(bytes: ByteArray): CryptoOuterClass.Signature {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(bytes)
            sign()
        }
        return CryptoOuterClass.Signature.newBuilder()
            .setFormat(CryptoOuterClass.SignatureFormat.SIGNATURE_FORMAT_DER)
            .setSignature(ByteString.copyFrom(signature))
            .setSigningAlgorithmSpec(
                CryptoOuterClass.SigningAlgorithmSpec.SIGNING_ALGORITHM_SPEC_EC_DSA_SHA_256
            )
            .build()
    }

    public companion object {
        private const val KEYSTORE = "AndroidKeyStore"

        /**
         * Generates a fresh keystore-resident P-256 key under [alias],
         * replacing any existing key with that alias.
         *
         * @param requireStrongBox fail with [StrongBoxUnavailableException]
         *   instead of falling back to the TEE.
         */
        public fun generate(
            alias: String,
            requireStrongBox: Boolean = false,
        ): AndroidKeystoreSigningDriver {
            val strongBoxSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            if (requireStrongBox && !strongBoxSupported) {
                throw IllegalStateException("StrongBox requires API 28+, running ${Build.VERSION.SDK_INT}")
            }

            fun spec(strongBox: Boolean): KeyGenParameterSpec =
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .apply {
                        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            setIsStrongBoxBacked(true)
                        }
                    }
                    .build()

            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            if (strongBoxSupported) {
                try {
                    generator.initialize(spec(strongBox = true))
                    generator.generateKeyPair()
                    return load(alias)
                        ?: throw IllegalStateException("generated key missing from keystore")
                } catch (unavailable: StrongBoxUnavailableException) {
                    if (requireStrongBox) throw unavailable
                }
            }
            generator.initialize(spec(strongBox = false))
            generator.generateKeyPair()
            return load(alias) ?: throw IllegalStateException("generated key missing from keystore")
        }

        /** The existing key under [alias], or null if none. */
        public fun load(alias: String): AndroidKeystoreSigningDriver? {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return null
            val publicKeyDer = keyStore.getCertificate(alias)?.publicKey?.encoded ?: return null
            return AndroidKeystoreSigningDriver(
                alias = alias,
                privateKey = privateKey,
                publicKeyDer = publicKeyDer,
                securityLevel = securityLevelOf(privateKey),
            )
        }

        /** Removes the key under [alias] from the keystore. */
        public fun delete(alias: String) {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }

        private fun securityLevelOf(privateKey: PrivateKey): SecurityLevel {
            val info = runCatching {
                KeyFactory.getInstance(privateKey.algorithm, KEYSTORE)
                    .getKeySpec(privateKey, KeyInfo::class.java)
            }.getOrNull() ?: return SecurityLevel.UNKNOWN

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                    KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
                    -> SecurityLevel.TRUSTED_ENVIRONMENT
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> SecurityLevel.SOFTWARE
                    else -> SecurityLevel.UNKNOWN
                }
            }
            @Suppress("DEPRECATION")
            return if (info.isInsideSecureHardware) {
                SecurityLevel.TRUSTED_ENVIRONMENT
            } else {
                SecurityLevel.SOFTWARE
            }
        }
    }
}
