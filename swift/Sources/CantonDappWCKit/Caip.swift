// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// The CAIP identifiers WalletConnect speaks, and the one place Canton party ids
/// are reconciled with them.
///
/// WalletConnect names a chain as `namespace:reference` (CAIP-2) and an account
/// as `namespace:reference:address` (CAIP-10). A Canton network id is already
/// CAIP-2 (`canton:localnet`), so it doubles as the chain id. The friction is
/// the account address: a party id is `hint::1220<fingerprint>`, and the `::` —
/// plus any `_` in a hint — falls outside the CAIP-10 address charset
/// (`[-.%a-zA-Z0-9]`). So a party is percent-encoded into the address segment,
/// and recovered by the inverse. Canton has no registered WalletConnect
/// namespace, so `canton` is a convention of this SDK.
///
/// The Swift mirror of Kotlin `object Caip`. `chainId`, `decodeParty` and
/// `partyFromAccount` throw ``CaipError`` where the Kotlin uses `require`.
public enum Caip {

    /// WalletConnect namespace for Canton.
    public static let cantonNamespace = "canton"

    /// A malformed CAIP identifier — a mistyped chain id, or a truncated
    /// percent-escape in an address segment.
    public struct CaipError: Error, Equatable {
        public let message: String
        public init(_ message: String) { self.message = message }
    }

    /// Validates a CAIP-2 chain id and returns it. A Canton `networkId` is
    /// already CAIP-2, so this guards a mistyped value before it reaches a
    /// relay rather than transforming anything.
    public static func chainId(_ networkId: String) throws -> String {
        guard isCaip2(networkId) else {
            throw CaipError("networkId '\(networkId)' is not a CAIP-2 chain id (namespace:reference)")
        }
        return networkId
    }

    /// Percent-encodes a party id into a CAIP-10 address segment: every UTF-8
    /// byte outside `[-.A-Za-z0-9]` becomes `%XX` (upper-case), so `::` → `%3A%3A`
    /// and `_` → `%5F`. ``decodeParty(_:)`` is the inverse.
    public static func encodeParty(_ partyId: String) -> String {
        var out = ""
        out.reserveCapacity(partyId.utf8.count + 8)
        for byte in partyId.utf8 {
            let ch = Character(Unicode.Scalar(byte))
            if isAddressSafe(ch) {
                out.append(ch)
            } else {
                out.append("%")
                out.append(hex[Int(byte >> 4)])
                out.append(hex[Int(byte & 0x0F)])
            }
        }
        return out
    }

    /// Recovers a party id from a CAIP-10 address segment.
    public static func decodeParty(_ address: String) throws -> String {
        var bytes: [UInt8] = []
        let chars = Array(address)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if c == "%" {
                guard i + 3 <= chars.count else {
                    throw CaipError("truncated percent-escape in '\(address)'")
                }
                guard let byte = UInt8(String(chars[(i + 1)..<(i + 3)]), radix: 16) else {
                    throw CaipError("invalid percent-escape in '\(address)'")
                }
                bytes.append(byte)
                i += 3
            } else {
                guard let ascii = c.asciiValue else {
                    throw CaipError("non-ASCII byte in CAIP-10 address '\(address)'")
                }
                bytes.append(ascii)
                i += 1
            }
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    /// Builds the CAIP-10 account (`chain:encodedParty`) a session advertises.
    public static func account(chainId: String, partyId: String) -> String {
        "\(chainId):\(encodeParty(partyId))"
    }

    /// Extracts the party id from a CAIP-10 account. The address segment carries
    /// no literal `:` (they are percent-encoded), so the last `:` splits chain
    /// from address unambiguously.
    public static func partyFromAccount(_ account: String) throws -> String {
        guard let cut = account.lastIndex(of: ":") else {
            throw CaipError("not a CAIP-10 account: '\(account)'")
        }
        return try decodeParty(String(account[account.index(after: cut)...]))
    }

    // ── internals ──────────────────────────────────────────────────────

    private static let hex = Array("0123456789ABCDEF")

    /// CAIP-2 `namespace:reference`: namespace `[-a-z0-9]{3,8}`, reference
    /// `[-_a-zA-Z0-9]{1,32}`. Matched by hand rather than with a regex so this
    /// carries no stored `Regex` (not `Sendable` under strict concurrency).
    private static func isCaip2(_ s: String) -> Bool {
        guard let colon = s.firstIndex(of: ":") else { return false }
        let namespace = s[s.startIndex..<colon]
        let reference = s[s.index(after: colon)...]
        guard (3...8).contains(namespace.count),
              namespace.allSatisfy({ $0 == "-" || isLower($0) || isDigit($0) })
        else { return false }
        guard (1...32).contains(reference.count),
              reference.allSatisfy({ $0 == "-" || $0 == "_" || isLower($0) || isUpper($0) || isDigit($0) })
        else { return false }
        return true
    }

    private static func isAddressSafe(_ c: Character) -> Bool {
        isLower(c) || isUpper(c) || isDigit(c) || c == "." || c == "-"
    }

    private static func isLower(_ c: Character) -> Bool { ("a"..."z").contains(c) }
    private static func isUpper(_ c: Character) -> Bool { ("A"..."Z").contains(c) }
    private static func isDigit(_ c: Character) -> Bool { ("0"..."9").contains(c) }
}
