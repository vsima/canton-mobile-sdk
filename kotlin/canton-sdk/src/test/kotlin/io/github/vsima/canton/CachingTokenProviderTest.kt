// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CachingTokenProviderTest {

    /** Unsigned JWT-shaped token whose payload carries the given claims. */
    private fun jwt(payload: String): String {
        fun b64(s: String) =
            Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        return "${b64("""{"alg":"none"}""")}.${b64(payload)}.sig"
    }

    @Test
    fun `parses the exp claim`() {
        assertEquals(
            Instant.ofEpochSecond(1_800_000_000),
            CachingTokenProvider.jwtExpiry(jwt("""{"sub":"u","exp":1800000000}""")),
        )
    }

    @Test
    fun `no exp, not a jwt, or garbage all mean no expiry`() {
        assertNull(CachingTokenProvider.jwtExpiry(jwt("""{"sub":"u"}""")))
        assertNull(CachingTokenProvider.jwtExpiry("opaque-token"))
        assertNull(CachingTokenProvider.jwtExpiry("a.###not-base64###.c"))
    }

    @Test
    fun `caches until shortly before expiry`() = runBlocking {
        var now = Instant.ofEpochSecond(1_000)
        var fetches = 0
        val provider = CachingTokenProvider(clock = { now }) {
            fetches++
            jwt("""{"exp":${now.epochSecond + 100}}""")
        }

        val first = provider.token()
        assertEquals(first, provider.token())
        assertEquals(1, fetches, "a fresh token must be served from cache")

        // Within the 30s leeway of exp=1100: refresh.
        now = Instant.ofEpochSecond(1_075)
        val second = provider.token()
        assertEquals(2, fetches, "a token near expiry must be refetched")
        assertTrue(second != first)
    }

    @Test
    fun `a token without exp is cached indefinitely`() = runBlocking {
        var fetches = 0
        val provider = CachingTokenProvider { fetches++; "opaque" }
        repeat(50) { provider.token() }
        assertEquals(1, fetches)
    }

    @Test
    fun `refreshIfChanged reports whether a retry can help`() = runBlocking {
        val tokens = ArrayDeque(listOf("t1", "t2", "t2"))
        val provider = CachingTokenProvider { tokens.removeFirst() }

        assertEquals("t1", provider.token())
        assertTrue(provider.refreshIfChanged("t1"), "a different token is worth a retry")
        assertEquals("t2", provider.cached())
        assertFalse(provider.refreshIfChanged("t2"), "the same token again means the failure is final")
    }
}
