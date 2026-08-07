package io.github.vsima.canton

/**
 * Configuration for connecting to a Canton participant's Ledger API.
 *
 * @property host hostname of the participant node exposing the gRPC Ledger API.
 * @property port Ledger API port. Canton's conventional default is 6865.
 * @property useTls whether to use TLS. Only disable for local development.
 * @property accessTokenProvider called before requests to produce a JWT access
 *   token for the Ledger API's `authorization: Bearer <token>` header, or null
 *   for unauthenticated (development) ledgers.
 * @property retryPolicy backoff applied to retryable ledger errors.
 */
public data class CantonClientConfiguration(
    val host: String,
    val port: Int = 6865,
    val useTls: Boolean = true,
    val accessTokenProvider: (() -> String)? = null,
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
)
