// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletStoreAndDelegatingDriverTest {

    /** The custody hook composes: a delegating driver wrapping any backend
     *  produces signatures the wrapped key verifies. */
    @Test
    fun `delegating driver round-trips signatures through its callbacks`() = runBlocking {
        val backend = SoftwareSigningDriver.generate(SoftwareSigningDriver.Algorithm.EC_P256)
        var signCalls = 0
        val driver = DelegatingSigningDriver(
            publicKeyProvider = { backend.publicKey() },
            signer = { bytes ->
                signCalls++
                backend.sign(bytes)
            },
        )

        val hash = ByteArray(32) { it.toByte() }
        val publicKey = driver.publicKey()
        val signature = driver.sign(hash)

        assertEquals(1, signCalls)
        val verifier = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initVerify(
                KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(publicKey.keyData.toByteArray()))
            )
            update(hash)
        }
        assertTrue(verifier.verify(signature.signature.toByteArray()))
    }

    @Test
    fun `in-memory store saves, lists in order, finds, and deletes`() = runBlocking {
        val store: WalletStore = InMemoryWalletStore()
        val first = WalletRecord("alice::1220aa", "1220ff", "sync::1", byteArrayOf(1, 2), Instant.EPOCH)
        val second = WalletRecord("bob::1220bb", "1220ee", "sync::1", null, Instant.EPOCH)

        store.save(first)
        store.save(second)
        assertEquals(listOf(first, second), store.list())
        assertEquals(first, store.find("alice::1220aa"))

        // Saving the same party replaces its record.
        val renewed = first.copy(publicKeyFingerprint = "1220dd")
        store.save(renewed)
        assertEquals(renewed, store.find("alice::1220aa"))

        store.delete("alice::1220aa")
        assertNull(store.find("alice::1220aa"))
        assertEquals(listOf(second), store.list())
    }
}
