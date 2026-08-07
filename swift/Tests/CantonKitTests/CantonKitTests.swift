// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Testing
@testable import CantonKit

@Suite struct CantonClientConfigurationTests {
    @Test func defaultsMatchCantonConventions() {
        let configuration = CantonClientConfiguration(host: "localhost")
        #expect(configuration.port == 6865)
        #expect(configuration.useTLS)
        #expect(configuration.accessTokenProvider == nil)
    }

    @Test func clientRetainsConfiguration() {
        let client = CantonClient(
            configuration: .init(host: "validator.example.com", port: 443)
        )
        #expect(client.configuration.host == "validator.example.com")
        #expect(client.configuration.port == 443)
    }
}
