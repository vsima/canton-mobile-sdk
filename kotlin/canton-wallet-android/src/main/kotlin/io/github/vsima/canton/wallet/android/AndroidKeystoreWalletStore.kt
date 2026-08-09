// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import io.github.vsima.canton.wallet.WalletRecord
import io.github.vsima.canton.wallet.WalletStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

/**
 * Durable [WalletStore] for Android: records are encrypted with an AES-GCM
 * key held in the Android Keystore, and Jetpack DataStore owns the file.
 * The Apple counterpart is `KeychainWalletStore`.
 *
 * Be precise about what this protects. A record binds a party to a *key
 * handle*, and for [AndroidKeystoreSigningDriver] that handle is a keystore
 * alias — the private key itself never leaves StrongBox or the TEE either
 * way. Encrypting the file protects the binding's integrity and keeps the
 * party id (a pseudonymous on-ledger identity) out of plain storage; it is
 * not what keeps signing keys secret.
 *
 * Two deliberate choices:
 *
 * - **DataStore, not `EncryptedSharedPreferences`.** Jetpack Security's
 *   crypto library is deprecated as of `security-crypto` 1.1.0-alpha07,
 *   with a history of keyset-corruption crashes and main-thread violations.
 *   DataStore is the current Jetpack storage layer: writes are atomic, one
 *   writer per file is enforced, and all I/O is off the main thread. This
 *   class supplies the encryption DataStore does not do itself.
 * - **No silent corruption recovery.** A store that can't be read raises
 *   [WalletStoreUnreadableException] rather than resetting to empty, since
 *   a wallet that quietly forgets its party will onboard a second identity.
 *
 * **Exclude the file from cloud backup.** The Keystore key is device-bound
 * and cannot be backed up, so a restored copy on a new device is
 * undecryptable. Set `android:allowBackup="false"` (what a wallet generally
 * wants) or exclude the store file in your backup rules.
 *
 * DataStore permits only one active instance per file, so instances for the
 * same path share one under the hood; construct them freely.
 *
 * Requires API 26: [WalletRecord.createdAt] is a `java.time.Instant`. Below
 * that, enable core library desugaring in the consuming app.
 *
 * @param fileName name of the store file inside `filesDir`.
 * @param keyAlias Android Keystore alias for the AES key that wraps it.
 */
@RequiresApi(Build.VERSION_CODES.O)
public class AndroidKeystoreWalletStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : WalletStore {

    private val file: File = File(context.applicationContext.filesDir, fileName)

    override suspend fun save(record: WalletRecord) {
        edit { records ->
            val existing = records.indexOfFirst { it.partyId == record.partyId }
            if (existing >= 0) {
                records.toMutableList().also { it[existing] = record }
            } else {
                records + record
            }
        }
    }

    override suspend fun list(): List<WalletRecord> = unwrapping { data().data.first() }

    override suspend fun find(partyId: String): WalletRecord? =
        list().firstOrNull { it.partyId == partyId }

    override suspend fun delete(partyId: String) {
        edit { records -> records.filterNot { it.partyId == partyId } }
    }

    /**
     * Drops every record by deleting the file — the recovery path once a
     * store has become unreadable, after which the wallet must re-onboard.
     * Unlike the other operations it does not read first, so it works on a
     * store that can no longer be decrypted.
     *
     * Leaves the keystore key in place; the next write reuses it.
     */
    public suspend fun clear() {
        close()
        file.delete()
    }

    /**
     * Releases the DataStore this process holds for the file, along with the
     * in-memory copy of its contents. The next operation reopens it, so
     * calling this is never required in normal use — reach for it when
     * something outside the store is about to touch the file, or to drop
     * decrypted records from memory.
     */
    public suspend fun close() {
        release(file)
    }

    private suspend fun edit(block: (List<WalletRecord>) -> List<WalletRecord>) {
        unwrapping { data().updateData { current -> block(current) } }
    }

    private fun data(): DataStore<List<WalletRecord>> = dataStoreFor(file, keyAlias)

    /**
     * DataStore surfaces a serializer's failure through its own wrapper, so
     * dig the meaningful exception back out instead of reporting an opaque
     * I/O error.
     */
    private suspend fun <T> unwrapping(block: suspend () -> T): T =
        try {
            block()
        } catch (error: Throwable) {
            throw generateSequence(error) { it.cause }
                .filterIsInstance<WalletStoreUnreadableException>()
                .firstOrNull() ?: error
        }

    public companion object {
        public const val DEFAULT_FILE_NAME: String = "canton-wallet-store"
        public const val DEFAULT_KEY_ALIAS: String = "canton-wallet-store"

        private val lock = Any()
        private val stores = HashMap<String, Handle>()

        private class Handle(val store: DataStore<List<WalletRecord>>, val scope: CoroutineScope)

        private fun dataStoreFor(file: File, keyAlias: String): DataStore<List<WalletRecord>> =
            synchronized(lock) {
                stores.getOrPut(file.absolutePath) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    Handle(
                        DataStoreFactory.create(
                            serializer = EncryptedRecordsSerializer(keyAlias),
                            // No corruption handler on purpose: a store that
                            // fails to decrypt must be reported, not reset.
                            scope = scope,
                            produceFile = { file },
                        ),
                        scope,
                    )
                }.store
            }

        /**
         * Retires the DataStore holding [file] so the file can be deleted
         * and a later call can open a fresh one. DataStore releases its
         * claim on a file when the scope it was created with completes.
         */
        private suspend fun release(file: File) {
            val handle = synchronized(lock) { stores.remove(file.absolutePath) } ?: return
            val job = handle.scope.coroutineContext[Job]
            handle.scope.cancel()
            job?.join()
        }
    }
}

/**
 * Encrypts the record list under an Android Keystore AES-GCM key.
 *
 * On-disk layout is `[format][iv length][iv][ciphertext+tag]`. The key
 * requires randomized encryption, so the IV comes from the keystore on each
 * write and travels with the payload.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class EncryptedRecordsSerializer(
    private val keyAlias: String,
) : Serializer<List<WalletRecord>> {

    override val defaultValue: List<WalletRecord> = emptyList()

    override suspend fun readFrom(input: InputStream): List<WalletRecord> {
        val blob = input.readBytes()
        if (blob.isEmpty()) return defaultValue
        if (blob.size < HEADER_BYTES || blob[0] != FORMAT_VERSION) {
            throw WalletStoreUnreadableException(
                "the wallet store header is not recognized (format ${blob.firstOrNull()})"
            )
        }
        val ivLength = blob[1].toInt()
        if (ivLength <= 0 || blob.size <= HEADER_BYTES + ivLength) {
            throw WalletStoreUnreadableException("the wallet store is truncated")
        }
        val iv = blob.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLength)
        val body = blob.copyOfRange(HEADER_BYTES + ivLength, blob.size)
        val plaintext = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, secretKey(keyAlias), GCMParameterSpec(TAG_BITS, iv))
                doFinal(body)
            }
        } catch (error: GeneralSecurityException) {
            throw WalletStoreUnreadableException(
                "the wallet store could not be decrypted: its keystore key ('$keyAlias') is " +
                    "gone or the file was altered. Keystore keys are device-bound and do not " +
                    "survive a backup restore — call clear() and re-onboard.",
                error,
            )
        }
        return WalletRecordCodec.decode(plaintext)
    }

    override suspend fun writeTo(t: List<WalletRecord>, output: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey(keyAlias))
        }
        val body = cipher.doFinal(WalletRecordCodec.encode(t))
        val iv = cipher.iv
        output.write(byteArrayOf(FORMAT_VERSION, iv.size.toByte()))
        output.write(iv)
        output.write(body)
    }

    private companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val AES_KEY_BITS = 256
        private const val FORMAT_VERSION: Byte = 1

        /** Format byte + IV length byte. */
        private const val HEADER_BYTES = 2

        /**
         * Generating a key with an alias that already exists replaces it,
         * orphaning everything encrypted under the old one. Serialize
         * get-or-create across every store in this process.
         */
        private val keyGuard = Any()

        fun secretKey(alias: String): SecretKey = synchronized(keyGuard) {
            val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keystore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            existing?.secretKey ?: generateKey(alias)
        }

        private fun generateKey(alias: String): SecretKey =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(AES_KEY_BITS)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }.generateKey()
    }
}

/**
 * The store exists but its contents can't be recovered — most often because
 * the Keystore key is gone (a restore onto another device, cleared
 * credentials), otherwise because the file was truncated or altered.
 *
 * Never swallow this into an empty store: a wallet that silently forgets its
 * party will re-onboard and allocate a second identity. Surface it, and use
 * [AndroidKeystoreWalletStore.clear] once the user has chosen to start over.
 */
public class WalletStoreUnreadableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
