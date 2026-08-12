// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `signMessage` domain-separation scheme, checked against the shared
 * golden vector (`testdata/dapp/signmessage.json`) that the Swift suite reads
 * too. Byte-exact agreement here is what lets a wallet on one platform and a
 * dApp on the other interoperate on a signed message.
 */
class DappSignMessageTest {

    private val vector: JsonObject =
        Json.parseToJsonElement(File("../../testdata/dapp/signmessage.json").readText()) as JsonObject

    @Test
    fun `signing bytes match the shared golden vector`() {
        val message = vector.getValue("message").jsonPrimitive.content
        val expectedHex = vector.getValue("signingBytesHex").jsonPrimitive.content

        val actualHex = DappSignMessage.signingBytes(message).toHex()

        assertEquals(expectedHex, actualHex, "signing bytes diverged from the shared vector")
    }

    @Test
    fun `the domain matches the vector and exceeds a transaction hash in length`() {
        assertEquals(vector.getValue("domain").jsonPrimitive.content, DappSignMessage.DOMAIN)
        // The structural guarantee: a 32-byte prepared transaction hash can
        // never equal these signing bytes, because the domain alone is longer.
        assertTrue(
            DappSignMessage.DOMAIN.encodeToByteArray().size > 32,
            "the domain must exceed 32 bytes for the separation to be structural",
        )
        assertTrue(DappSignMessage.signingBytes("").size > 32)
    }

    @Test
    fun `the domain prefixes the signing bytes`() {
        val bytes = DappSignMessage.signingBytes("hello")
        assertTrue(bytes.toHex().startsWith(DappSignMessage.DOMAIN.encodeToByteArray().toHex()))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
