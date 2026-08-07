package io.github.vsima.canton.wallet

import com.daml.ledger.api.v2.CryptoOuterClass
import com.google.protobuf.ByteString
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Produces Canton [CryptoOuterClass.Signature]s over ledger-provided hashes.
 *
 * Implementations decide where the private key lives: in software, in the
 * Android Keystore / StrongBox (see the android module), or in a custody
 * provider (Fireblocks, BitGo, ...). The driver never sees a full
 * transaction — only the hash the ledger asks the party to sign, which is
 * what makes hardware-backed and custody-held keys possible.
 *
 * The returned signature carries no `signed_by` fingerprint; callers that
 * know the canonical fingerprint (e.g. from
 * `GenerateExternalPartyTopologyResponse.public_key_fingerprint`) complete
 * it via [CryptoOuterClass.Signature.toBuilder].
 */
public interface SigningDriver {
    /** The public key in a Ledger-API-compatible encoding. */
    public suspend fun publicKey(): CryptoOuterClass.SigningPublicKey

    /** Signs [bytes] (a Canton-provided hash) with the driver's private key. */
    public suspend fun sign(bytes: ByteArray): CryptoOuterClass.Signature
}

/**
 * JCA-backed software keys, supporting the two schemes relevant to mobile
 * wallets: Ed25519 (Canton's default) and ECDSA P-256 (the only scheme
 * hardware enclaves sign — Apple Secure Enclave and Android StrongBox are
 * P-256-only). Both are accepted by the Ledger API; see crypto.proto.
 */
public class SoftwareSigningDriver private constructor(
    private val keyPair: KeyPair,
    public val algorithm: Algorithm,
) : SigningDriver {

    public enum class Algorithm { ED25519, EC_P256 }

    override suspend fun publicKey(): CryptoOuterClass.SigningPublicKey =
        CryptoOuterClass.SigningPublicKey.newBuilder()
            // JCA's getEncoded() is the X.509 SubjectPublicKeyInfo DER form.
            .setFormat(CryptoOuterClass.CryptoKeyFormat.CRYPTO_KEY_FORMAT_DER_X509_SUBJECT_PUBLIC_KEY_INFO)
            .setKeyData(ByteString.copyFrom(keyPair.public.encoded))
            .setKeySpec(
                when (algorithm) {
                    Algorithm.ED25519 -> CryptoOuterClass.SigningKeySpec.SIGNING_KEY_SPEC_EC_CURVE25519
                    Algorithm.EC_P256 -> CryptoOuterClass.SigningKeySpec.SIGNING_KEY_SPEC_EC_P256
                }
            )
            .build()

    override suspend fun sign(bytes: ByteArray): CryptoOuterClass.Signature {
        val (jcaName, format, spec) = when (algorithm) {
            Algorithm.ED25519 -> Triple(
                "Ed25519",
                CryptoOuterClass.SignatureFormat.SIGNATURE_FORMAT_CONCAT,
                CryptoOuterClass.SigningAlgorithmSpec.SIGNING_ALGORITHM_SPEC_ED25519,
            )
            Algorithm.EC_P256 -> Triple(
                "SHA256withECDSA",
                CryptoOuterClass.SignatureFormat.SIGNATURE_FORMAT_DER,
                CryptoOuterClass.SigningAlgorithmSpec.SIGNING_ALGORITHM_SPEC_EC_DSA_SHA_256,
            )
        }
        val signature = java.security.Signature.getInstance(jcaName).run {
            initSign(keyPair.private)
            update(bytes)
            sign()
        }
        return CryptoOuterClass.Signature.newBuilder()
            .setFormat(format)
            .setSignature(ByteString.copyFrom(signature))
            .setSigningAlgorithmSpec(spec)
            .build()
    }

    public companion object {
        public fun generate(algorithm: Algorithm): SoftwareSigningDriver {
            val keyPair = when (algorithm) {
                Algorithm.ED25519 -> KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                Algorithm.EC_P256 -> KeyPairGenerator.getInstance("EC")
                    .apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
            }
            return SoftwareSigningDriver(keyPair, algorithm)
        }
    }
}
