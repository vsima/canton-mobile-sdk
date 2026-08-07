package io.github.vsima.canton.wallet

/**
 * Read layer over the Scan API (Splice) — WP3 skeleton.
 *
 * Balances, parsed transaction history (semantics matched to the official
 * TS SDK via golden vectors generated from its output), ANS name lookup,
 * amulet rules and open mining rounds. HTTP, not gRPC — shares the HTTP
 * client layer introduced by WP2.
 */
public class ScanClient(public val baseUrl: String) {

    public data class AnsEntry(val name: String, val partyId: String)

    public suspend fun balance(partyId: String, instrumentId: String): java.math.BigDecimal =
        TODO("WP3: scan balance endpoint")

    public suspend fun transactionHistory(partyId: String, pageSize: Int = 50): List<String> =
        TODO("WP3: parsed tx history, golden-vector matched against the TS SDK")

    public suspend fun lookupAnsName(name: String): AnsEntry? =
        TODO("WP3: ANS lookup for name-based sending")
}
