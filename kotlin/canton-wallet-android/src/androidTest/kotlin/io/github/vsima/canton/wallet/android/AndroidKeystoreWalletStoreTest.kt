// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vsima.canton.wallet.WalletRecord
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the encrypted wallet store: the keystore-backed
 * crypto and the file handling that the JVM codec tests can't reach.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreWalletStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val fileName = "canton-wallet-store-test-$suffix"
    private val keyAlias = "canton-wallet-store-test-$suffix"
    private val file = File(context.filesDir, fileName)

    private fun store() = AndroidKeystoreWalletStore(context, fileName, keyAlias)

    private fun record(
        partyId: String = "wallet::1220${suffix.take(8)}",
        keyHandle: ByteArray? = "wallet".toByteArray(),
    ) = WalletRecord(
        partyId = partyId,
        publicKeyFingerprint = "1220ffee",
        synchronizerId = "global-domain::1220aa",
        keyHandle = keyHandle,
        createdAt = Instant.ofEpochSecond(1_770_000_000L, 123_456_789L),
    )

    /** Decrypts the file without going through the store, so durability is
     *  asserted against the bytes on disk rather than a cached copy. */
    private fun decryptFile(): List<WalletRecord> {
        val blob = file.readBytes()
        val ivLength = blob[1].toInt()
        val key = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, blob.copyOfRange(2, 2 + ivLength)),
            )
            doFinal(blob.copyOfRange(2 + ivLength, blob.size))
        }
        return WalletRecordCodec.decode(plaintext)
    }

    @After
    fun tearDown() = runBlocking {
        store().close()
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }

    @Test
    fun persistsRecordsToDisk() = runBlocking {
        val original = record()
        store().save(original)

        // The bytes on disk hold the record — what plain in-memory storage
        // cannot do, and the reason this class exists.
        assertEquals(listOf(original), decryptFile())

        val reopened = store()
        assertEquals(listOf(original), reopened.list())
        assertEquals(original, reopened.find(original.partyId))
        assertNull(reopened.find("wallet::absent"))
    }

    @Test
    fun rereadsFromDiskAfterClose() = runBlocking {
        val original = record()
        val store = store()
        store.save(original)
        // Drops both the DataStore and its in-memory copy, so the next read
        // genuinely goes back to the file.
        store.close()

        assertEquals(listOf(original), store.list())
    }

    @Test
    fun writesCiphertextNotPlaintext() = runBlocking {
        val original = record(partyId = "wallet::needle-$suffix")
        store().save(original)

        val onDisk = withContext(Dispatchers.IO) { file.readBytes() }
        assertFalse(
            "the party id must not be readable in the store file",
            onDisk.toString(Charsets.ISO_8859_1).contains("needle"),
        )
        assertEquals("format version byte", 1.toByte(), onDisk[0])
    }

    @Test
    fun updatesAndDeletesRecords() = runBlocking {
        val store = store()
        val original = record()
        store.save(original)
        store.save(original.copy(synchronizerId = "global-domain::1220bb"))

        assertEquals(1, store.list().size)
        assertEquals("global-domain::1220bb", store.list().single().synchronizerId)

        store.delete(original.partyId)
        assertTrue(store.list().isEmpty())
        // Deleting an absent record is a no-op, not a failure.
        store.delete(original.partyId)
    }

    @Test
    fun clearEmptiesTheStore() = runBlocking {
        val store = store()
        store.save(record())
        store.clear()

        assertTrue(store.list().isEmpty())
        assertFalse(file.exists())
        // Still usable afterwards: the keystore key survives a clear.
        store.save(record())
        assertEquals(1, store.list().size)
    }

    @Test
    fun reportsAnAlteredFileInsteadOfLosingRecords() = runBlocking {
        val store = store()
        store.save(record())
        store.close()

        withContext(Dispatchers.IO) {
            val corrupted = file.readBytes()
            corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()
            file.writeBytes(corrupted)
        }

        // Silently returning an empty store here would make the wallet
        // re-onboard and allocate a second party.
        assertThrows(WalletStoreUnreadableException::class.java) {
            runBlocking { store.list() }
        }
    }

    @Test
    fun reportsAMissingKeystoreKey() = runBlocking {
        val store = store()
        store.save(record())
        store.close()

        // What a cloud-backup restore onto another device looks like: the
        // file survives, the device-bound key does not.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

        val failure = assertThrows(WalletStoreUnreadableException::class.java) {
            runBlocking { store.list() }
        }
        assertNotNull(failure.message)
        assertTrue(failure.message!!.contains("clear()"))

        // The documented recovery path works.
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun concurrentSavesAllLand() = runBlocking {
        val store = store()
        val parties = (1..16).map { "wallet::concurrent-$it" }

        parties.map { party -> async(Dispatchers.IO) { store.save(record(partyId = party)) } }
            .awaitAll()

        // Read-modify-write through DataStore: no update may be lost.
        assertEquals(parties.toSet(), store.list().map { it.partyId }.toSet())
    }
}
