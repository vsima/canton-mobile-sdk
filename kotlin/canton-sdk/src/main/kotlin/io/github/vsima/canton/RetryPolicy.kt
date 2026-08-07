package io.github.vsima.canton

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Exponential backoff policy applied to retryable ledger errors
 * (see [CantonError.retryable]). A server-suggested delay from `RetryInfo`
 * takes precedence over the computed backoff when it is longer.
 */
public data class RetryPolicy(
    /** Total attempts including the first; 1 disables retries. */
    val maxAttempts: Int = 4,
    val initialBackoff: Duration = 250.milliseconds,
    val backoffMultiplier: Double = 2.0,
    val maxBackoff: Duration = 5.seconds,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    public companion object {
        public val DEFAULT: RetryPolicy = RetryPolicy()
        public val NONE: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }

    internal fun backoffFor(attempt: Int): Duration {
        var backoff = initialBackoff
        repeat(attempt - 1) {
            backoff = (backoff * backoffMultiplier).coerceAtMost(maxBackoff)
        }
        return backoff * Random.nextDouble(0.8, 1.2)
    }
}

/**
 * Runs [block], retrying [CantonException]s that are retryable. The block is
 * responsible for using a stable command id across attempts (which
 * [CommandSubmission] guarantees), so retries are deduplicated server-side.
 */
internal suspend fun <T> withRetries(policy: RetryPolicy, block: suspend () -> T): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: CantonException) {
            if (!e.error.retryable || attempt >= policy.maxAttempts) throw e
            delay(maxOf(policy.backoffFor(attempt), e.error.retryDelay ?: Duration.ZERO))
            attempt++
        }
    }
}
