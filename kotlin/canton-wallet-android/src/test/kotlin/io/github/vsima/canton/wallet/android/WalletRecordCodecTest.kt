// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet.android

import io.github.vsima.canton.wallet.WalletRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The encoding half of [AndroidKeystoreWalletStore], which runs on the JVM —
 * the keystore half needs a device and lives in `androidTest`.
 */
class WalletRecordCodecTest {

    private fun record(
        partyId: String = "wallet::1220abcd",
        fingerprint: String = "1220ffee",
        synchronizer: String = "global-domain::1220aa",
        keyHandle: ByteArray? = byteArrayOf(0x00, 0x7f, -0x80, -0x01),
        createdAt: Instant = Instant.ofEpochSecond(1_770_000_000L, 123_456_789L),
    ) = WalletRecord(partyId, fingerprint, synchronizer, keyHandle, createdAt)

    @Test
    fun `round trips a record`() {
        val original = record()
        val decoded = WalletRecordCodec.decode(WalletRecordCodec.encode(listOf(original)))

        // WalletRecord.equals compares key handles by content, so this also
        // covers the hex encoding.
        assertEquals(listOf(original), decoded)
    }

    @Test
    fun `preserves sub-millisecond timestamps`() {
        // Storing epoch millis would silently drop these digits and break
        // equality for a record that was just saved.
        val original = record(createdAt = Instant.ofEpochSecond(1_770_000_000L, 987_654_321L))
        val decoded = WalletRecordCodec.decode(WalletRecordCodec.encode(listOf(original)))

        assertEquals(987_654_321, decoded.single().createdAt.nano)
    }

    @Test
    fun `round trips a null key handle`() {
        val original = record(keyHandle = null)
        val decoded = WalletRecordCodec.decode(WalletRecordCodec.encode(listOf(original)))

        assertNull(decoded.single().keyHandle)
    }

    @Test
    fun `round trips an empty key handle distinctly from null`() {
        val decoded = WalletRecordCodec.decode(
            WalletRecordCodec.encode(listOf(record(keyHandle = ByteArray(0))))
        )

        assertContentEquals(ByteArray(0), decoded.single().keyHandle)
    }

    @Test
    fun `keeps insertion order across many records`() {
        val records = (1..5).map { record(partyId = "wallet::$it") }
        val decoded = WalletRecordCodec.decode(WalletRecordCodec.encode(records))

        assertEquals(records.map { it.partyId }, decoded.map { it.partyId })
    }

    @Test
    fun `round trips an empty store`() {
        assertTrue(WalletRecordCodec.decode(WalletRecordCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `survives non-ascii and long values`() {
        val original = record(partyId = "wallet::ünïcode-🔐", fingerprint = "f".repeat(4096))
        val decoded = WalletRecordCodec.decode(WalletRecordCodec.encode(listOf(original)))

        assertEquals(original, decoded.single())
    }

    @Test
    fun `rejects an unknown format version`() {
        val forged = """{"version":99,"records":[]}""".toByteArray()

        assertFailsWith<WalletStoreUnreadableException> { WalletRecordCodec.decode(forged) }
    }

    @Test
    fun `rejects malformed json`() {
        assertFailsWith<WalletStoreUnreadableException> {
            WalletRecordCodec.decode("not json at all".toByteArray())
        }
    }

    @Test
    fun `rejects a record missing a required field`() {
        val forged = """{"version":1,"records":[{"partyId":"wallet::1"}]}""".toByteArray()

        assertFailsWith<WalletStoreUnreadableException> { WalletRecordCodec.decode(forged) }
    }

    @Test
    fun `rejects a key handle that is not hex`() {
        val forged = """
            {"version":1,"records":[{"partyId":"p","publicKeyFingerprint":"f",
            "synchronizerId":"s","keyHandle":"zz","createdAtEpochSecond":1,"createdAtNano":0}]}
        """.trimIndent().toByteArray()

        assertFailsWith<WalletStoreUnreadableException> { WalletRecordCodec.decode(forged) }
    }
}
