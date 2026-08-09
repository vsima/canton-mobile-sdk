// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.vsima.canton.wallet.WalletRecord
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The on-disk shape of [AndroidKeystoreWalletStore]'s records: UTF-8 JSON,
 * built through the `JsonElement` API so no serialization compiler plugin
 * (and no reflection for R8 to keep) is involved.
 *
 * `createdAt` is stored as epoch seconds plus a nanosecond adjustment rather
 * than milliseconds, so a saved record round-trips [WalletRecord.equals] on
 * platforms whose clock has sub-millisecond resolution. Key handles are hex
 * rather than Base64: `java.util.Base64` needs API 26 and `android.util.Base64`
 * can't run in JVM unit tests, and these blobs are short.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal object WalletRecordCodec {

    private const val FORMAT = 1

    fun encode(records: Collection<WalletRecord>): ByteArray =
        buildJsonObject {
            put("version", JsonPrimitive(FORMAT))
            put(
                "records",
                buildJsonArray {
                    // A plain loop, not Iterable.forEach: on a Java
                    // collection type that resolves to the platform's
                    // default method, which needs API 24.
                    for (record in records) {
                        add(
                            buildJsonObject {
                                put("partyId", JsonPrimitive(record.partyId))
                                put("publicKeyFingerprint", JsonPrimitive(record.publicKeyFingerprint))
                                put("synchronizerId", JsonPrimitive(record.synchronizerId))
                                put(
                                    "keyHandle",
                                    record.keyHandle?.let { JsonPrimitive(it.toHex()) } ?: JsonNull,
                                )
                                put("createdAtEpochSecond", JsonPrimitive(record.createdAt.epochSecond))
                                put("createdAtNano", JsonPrimitive(record.createdAt.nano))
                            }
                        )
                    }
                }
            )
        }.toString().toByteArray(Charsets.UTF_8)

    /** Records in stored order, oldest first. */
    fun decode(bytes: ByteArray): List<WalletRecord> {
        val root = parse(bytes)
        val version = root["version"]?.jsonPrimitive?.int
        if (version != FORMAT) {
            throw WalletStoreUnreadableException(
                "unsupported wallet store format $version (this build reads $FORMAT)"
            )
        }
        return root.array("records").map { element -> element.record() }
    }

    private fun parse(bytes: ByteArray): JsonObject =
        try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (error: RuntimeException) {
            // kotlinx-serialization signals malformed input with
            // SerializationException; jsonObject casts throw IllegalArgument.
            throw WalletStoreUnreadableException("the wallet store is not valid JSON", error)
        }

    private fun JsonObject.array(name: String): JsonArray =
        try {
            this[name]?.jsonArray ?: JsonArray(emptyList())
        } catch (error: IllegalArgumentException) {
            throw WalletStoreUnreadableException("'$name' is not an array in the wallet store", error)
        }

    private fun kotlinx.serialization.json.JsonElement.record(): WalletRecord =
        try {
            val fields = jsonObject
            WalletRecord(
                partyId = fields.text("partyId"),
                publicKeyFingerprint = fields.text("publicKeyFingerprint"),
                synchronizerId = fields.text("synchronizerId"),
                keyHandle = fields["keyHandle"]
                    ?.takeIf { it !is JsonNull }
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.fromHex(),
                createdAt = Instant.ofEpochSecond(
                    fields["createdAtEpochSecond"]?.jsonPrimitive?.long
                        ?: throw WalletStoreUnreadableException("record is missing 'createdAtEpochSecond'"),
                    (fields["createdAtNano"]?.jsonPrimitive?.long ?: 0L),
                ),
            )
        } catch (error: IllegalArgumentException) {
            throw WalletStoreUnreadableException("a wallet store record is malformed", error)
        }

    private fun JsonObject.text(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?: throw WalletStoreUnreadableException("record is missing '$name'")

    private fun ByteArray.toHex(): String {
        val hex = StringBuilder(size * 2)
        forEach { byte ->
            val value = byte.toInt() and 0xff
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return hex.toString()
    }

    private fun String.fromHex(): ByteArray {
        if (length % 2 != 0) {
            throw WalletStoreUnreadableException("key handle is not valid hex")
        }
        return ByteArray(length / 2) { index ->
            val high = HEX.indexOf(this[index * 2].lowercaseChar())
            val low = HEX.indexOf(this[index * 2 + 1].lowercaseChar())
            if (high < 0 || low < 0) {
                throw WalletStoreUnreadableException("key handle is not valid hex")
            }
            ((high shl 4) or low).toByte()
        }
    }

    private const val HEX = "0123456789abcdef"
}
