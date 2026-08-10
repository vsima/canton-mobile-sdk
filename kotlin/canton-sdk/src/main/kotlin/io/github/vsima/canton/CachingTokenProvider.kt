// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

import java.time.Instant
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches the token from an access-token provider until shortly before it
 * expires, so the provider is consulted once per token lifetime instead of
 * once per request.
 *
 * Expiry comes from the token itself: Ledger API tokens are JWTs, and the
 * standard `exp` claim is read from the payload. A token without a parseable
 * `exp` is cached until [refreshIfChanged] forces a fetch — the right
 * behaviour for non-expiring development tokens.
 *
 * [refreshIfChanged] exists for the failure path: when the server rejects a
 * connection for authentication reasons, the caller fetches fresh and learns
 * whether retrying can possibly help. A provider that returns the same token
 * it returned before has nothing new to offer, and the rejection is final.
 */
public class CachingTokenProvider(
    private val refreshLeeway: Duration = 30.seconds,
    private val clock: () -> Instant = Instant::now,
    private val fetch: suspend () -> String,
) {
    private val mutex = Mutex()

    @Volatile
    private var token: String? = null
    private var expiresAt: Instant? = null

    /** The cached token, refreshed via [fetch] when within [refreshLeeway] of expiry. */
    public suspend fun token(): String = mutex.withLock {
        val cached = token
        val expiry = expiresAt
        if (cached != null &&
            (expiry == null || clock().isBefore(expiry.minusMillis(refreshLeeway.inWholeMilliseconds)))
        ) {
            cached
        } else {
            refreshLocked()
        }
    }

    /** Last token handed out, without fetching. Null until the first [token] call. */
    public fun cached(): String? = token

    /**
     * Forces a fetch and reports whether the result differs from [previous].
     * `false` means the provider cannot supply anything newer — an
     * authentication failure seen with [previous] will repeat.
     */
    public suspend fun refreshIfChanged(previous: String?): Boolean = mutex.withLock {
        refreshLocked() != previous
    }

    private suspend fun refreshLocked(): String {
        val fresh = fetch()
        token = fresh
        expiresAt = jwtExpiry(fresh)
        return fresh
    }

    internal companion object {
        /**
         * The `exp` claim of a JWT, or null when [token] is not a JWT or
         * carries none. A targeted scan rather than JSON parsing: `exp` is a
         * top-level integer claim (RFC 7519), and this module has no JSON
         * dependency.
         */
        internal fun jwtExpiry(token: String): Instant? {
            val segments = token.split('.')
            if (segments.size != 3) return null
            val payload = try {
                String(Base64.getUrlDecoder().decode(segments[1]))
            } catch (_: IllegalArgumentException) {
                return null
            }
            val match = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload) ?: return null
            return Instant.ofEpochSecond(match.groupValues[1].toLong())
        }
    }
}
