// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// The exact bytes a `signMessage` request signs — and the reason they are not
/// simply the message's bytes.
///
/// A wallet's account key also signs transaction hashes (the prepared
/// transaction the ledger asks the party to authorize). If `signMessage` signed
/// a caller-supplied string raw, a hostile dApp could hand the user a "message"
/// whose bytes are a transaction hash it wants authorized, and a signature over
/// it would be a valid ledger signature. Domain separation prevents that: every
/// `signMessage` signature is over a fixed prefix followed by the message, so it
/// lives in a byte space that can never overlap a transaction hash.
///
/// The separation is **structural, not merely conventional**: ``domain`` is
/// longer than 32 bytes, so ``signingBytes(_:)`` is always longer than 32 bytes,
/// while a prepared transaction hash is exactly 32. For Ed25519 — which signs
/// the bytes directly — the two can therefore never be equal for *any* message.
/// For ECDSA, which signs `SHA256(bytes)`, the domain sits inside the pre-image.
///
/// This lives in `CantonDappKit`, with no crypto, on purpose: **both** ends need
/// it. The wallet applies it before signing; a dApp verifying a signature (a
/// sign-in flow checking a signed nonce, say) must apply the identical transform
/// before verifying, or the signature will not validate.
public enum DappSignMessage {

    /// The domain-separation prefix. Versioned so the scheme can change without
    /// a silent signature-meaning shift, and deliberately longer than a 32-byte
    /// transaction hash (38 bytes) so ``signingBytes(_:)`` can never be one.
    ///
    /// **Interop-critical: this constant is byte-for-byte identical across the
    /// Kotlin and Swift SDKs.** A shared golden vector (`testdata/dapp/
    /// signmessage.json`) pins it so the two cannot drift.
    public static let domain = "CantonNetwork:CIP-0103:signMessage:v1\n"

    /// The bytes actually signed for `message`: ``domain`` then the UTF-8 message.
    public static func signingBytes(_ message: String) -> Data {
        Data((domain + message).utf8)
    }
}
