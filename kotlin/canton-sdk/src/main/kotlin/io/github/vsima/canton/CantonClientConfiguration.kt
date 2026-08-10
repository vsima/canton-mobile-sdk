// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton

/**
 * Configuration for connecting to a Canton participant's Ledger API.
 *
 * @property host hostname of the participant node exposing the gRPC Ledger API.
 * @property port Ledger API port. Canton's conventional default is 6865.
 * @property useTls whether to use TLS. Only disable for local development.
 * @property accessTokenProvider produces a JWT access token for the Ledger
 *   API's `authorization: Bearer <token>` header, or null for
 *   unauthenticated (development) ledgers. May suspend — an OIDC refresh
 *   belongs here directly. The client caches the token until shortly before
 *   its `exp` claim and re-invokes the provider only then, or when the
 *   server rejects a connection for authentication reasons — so the
 *   provider must return a current token when asked again, never a stored
 *   copy of the one that just failed.
 * @property tlsTrust which certificates to trust when [useTls] is on.
 *   Defaults to the platform trust store; pin an operator's CA to reject
 *   interception by a certificate authority the device happens to trust.
 * @property retryPolicy backoff applied to retryable ledger errors.
 */
public data class CantonClientConfiguration(
    val host: String,
    val port: Int = 6865,
    val useTls: Boolean = true,
    val accessTokenProvider: (suspend () -> String)? = null,
    val tlsTrust: TlsTrust = TlsTrust(),
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
)
