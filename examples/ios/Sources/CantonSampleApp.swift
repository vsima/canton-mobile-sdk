import CantonKit
import SwiftUI

/// Minimal smoke-test app: connects to a Canton participant and shows the
/// Ledger API version. Defaults to 127.0.0.1 (the simulator shares the
/// host's loopback) so it can reach a local `integration/run-canton.sh`
/// node. Override via `SIMCTL_CHILD_CANTON_HOST` / `SIMCTL_CHILD_CANTON_PORT`
/// when launching through `simctl`.
@main
struct CantonSampleApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @State private var status = "Connecting to ledger…"
    @State private var enclaveStatus = "Checking Secure Enclave…"

    var body: some View {
        VStack(spacing: 16) {
            Text(status)
            Text(enclaveStatus)
        }
            .font(.callout)
            .multilineTextAlignment(.center)
            .padding()
            .task {
                enclaveStatus = await EnclaveSelfCheck.run()
            }
            .task {
                let environment = ProcessInfo.processInfo.environment
                let client = CantonClient(
                    configuration: .init(
                        host: environment["CANTON_HOST"] ?? "127.0.0.1",
                        port: environment["CANTON_PORT"].flatMap(Int.init) ?? 6865,
                        useTLS: false
                    )
                )
                do {
                    status = "Ledger API version: \(try await client.ledgerApiVersion())"
                } catch {
                    status = "Could not reach ledger: \(error)"
                }
            }
    }
}
