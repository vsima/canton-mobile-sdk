// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.wallet

import java.time.Instant

/**
 * One onboarded wallet identity: the party, the fingerprint of its
 * registered key, and an opaque handle for reconstructing the signer.
 *
 * [keyHandle] is whatever the driver needs to come back to life — a Secure
 * Enclave `dataRepresentation`, a custody provider's key id, a keystore
 * alias. It is opaque to the SDK and MUST NOT contain raw private key
 * material for hardware/custody drivers (their handles are references, not
 * keys).
 */
public data class WalletRecord(
    val partyId: String,
    val publicKeyFingerprint: String,
    val synchronizerId: String,
    val keyHandle: ByteArray?,
    val createdAt: Instant,
) {
    override fun equals(other: Any?): Boolean =
        other is WalletRecord &&
            partyId == other.partyId &&
            publicKeyFingerprint == other.publicKeyFingerprint &&
            synchronizerId == other.synchronizerId &&
            (keyHandle ?: ByteArray(0)).contentEquals(other.keyHandle ?: ByteArray(0)) &&
            createdAt == other.createdAt

    override fun hashCode(): Int = partyId.hashCode()
}

/**
 * Persistence for wallet identities across launches. The SDK ships
 * [InMemoryWalletStore] for tests and composition; apps provide a durable
 * implementation (SQLDelight, Room, DataStore, files) — the surface is
 * deliberately small so that's a page of code.
 */
public interface WalletStore {
    public suspend fun save(record: WalletRecord)

    /** All records, oldest first. */
    public suspend fun list(): List<WalletRecord>

    public suspend fun find(partyId: String): WalletRecord?

    public suspend fun delete(partyId: String)
}

/** Non-durable [WalletStore]; suitable for tests and previews. */
public class InMemoryWalletStore : WalletStore {
    private val records = linkedMapOf<String, WalletRecord>()
    private val lock = Any()

    override suspend fun save(record: WalletRecord) {
        synchronized(lock) { records[record.partyId] = record }
    }

    override suspend fun list(): List<WalletRecord> =
        synchronized(lock) { records.values.toList() }

    override suspend fun find(partyId: String): WalletRecord? =
        synchronized(lock) { records[partyId] }

    override suspend fun delete(partyId: String) {
        synchronized(lock) { records.remove(partyId) }
    }
}
