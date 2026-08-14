// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.dapp.wc

import java.io.ByteArrayOutputStream

/**
 * The CAIP identifiers WalletConnect speaks, and the one place Canton party ids
 * are reconciled with them.
 *
 * WalletConnect names a chain as `namespace:reference` (CAIP-2) and an account
 * as `namespace:reference:address` (CAIP-10). A Canton network id is already
 * CAIP-2 (`canton:localnet`), so it doubles as the chain id. The friction is
 * the account address: a party id is `hint::1220<fingerprint>`, and the `::` —
 * plus any `_` in a hint — falls outside the CAIP-10 address charset
 * (`[-.%a-zA-Z0-9]`). So a party is percent-encoded into the address segment,
 * and recovered by the inverse. `canton` is WalletConnect's namespace for
 * Canton (`canton:<network-id>`, operator-defined), and this percent-encoded
 * account (`::` -> `%3A%3A`) matches its published Canton chain support.
 */
public object Caip {

    /** WalletConnect namespace for Canton. */
    public const val CANTON_NAMESPACE: String = "canton"

    private val CAIP2 = Regex("^[-a-z0-9]{3,8}:[-_a-zA-Z0-9]{1,32}$")

    /**
     * Validates a CAIP-2 chain id and returns it. A Canton `networkId` is
     * already CAIP-2, so this guards a mistyped value before it reaches a
     * relay rather than transforming anything.
     */
    public fun chainId(networkId: String): String {
        require(CAIP2.matches(networkId)) {
            "networkId '$networkId' is not a CAIP-2 chain id (namespace:reference)"
        }
        return networkId
    }

    /**
     * Percent-encodes a party id into a CAIP-10 address segment: every UTF-8
     * byte outside `[-.A-Za-z0-9]` becomes `%XX` (upper-case), so `::` → `%3A%3A`
     * and `_` → `%5F`. [decodeParty] is the inverse.
     */
    public fun encodeParty(partyId: String): String {
        val sb = StringBuilder(partyId.length + 8)
        for (b in partyId.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-') {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[v ushr 4]).append(HEX[v and 0xF])
            }
        }
        return sb.toString()
    }

    /** Recovers a party id from a CAIP-10 address segment. */
    public fun decodeParty(address: String): String {
        val bytes = ByteArrayOutputStream(address.length)
        var i = 0
        while (i < address.length) {
            val c = address[i]
            if (c == '%') {
                require(i + 3 <= address.length) { "truncated percent-escape in '$address'" }
                bytes.write(address.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(c.code)
                i += 1
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** Builds the CAIP-10 account (`chain:encodedParty`) a session advertises. */
    public fun account(chainId: String, partyId: String): String =
        "$chainId:${encodeParty(partyId)}"

    /**
     * Extracts the party id from a CAIP-10 account. The address segment carries
     * no literal `:` (they are percent-encoded), so the last `:` splits chain
     * from address unambiguously.
     */
    public fun partyFromAccount(account: String): String {
        val cut = account.lastIndexOf(':')
        require(cut >= 0) { "not a CAIP-10 account: '$account'" }
        return decodeParty(account.substring(cut + 1))
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
