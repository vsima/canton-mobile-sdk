package io.github.vsima.canton.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import io.github.vsima.canton.CantonClient
import io.github.vsima.canton.CantonClientConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal smoke-test app: connects to a Canton participant and shows the
 * Ledger API version. Defaults to 10.0.2.2 (the emulator's host loopback) so
 * it can reach a local `integration/run-canton.sh` node.
 */
class MainActivity : Activity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this)
        status.text = "Connecting to ledger…"
        status.textSize = 16f
        status.setPadding(48, 48, 48, 48)
        // Center in the window: API 35+ lays content out edge-to-edge, so
        // top-aligned text would sit behind the system/app bars.
        status.gravity = Gravity.CENTER
        // Explicit colors so the label is legible regardless of system theme.
        status.setTextColor(0xFF202124.toInt())
        status.setBackgroundColor(0xFFFFFFFF.toInt())
        setContentView(status)

        scope.launch {
            status.text = try {
                val version = withContext(Dispatchers.IO) {
                    CantonClient(
                        CantonClientConfiguration(
                            host = "10.0.2.2",
                            port = 6865,
                            useTls = false,
                        )
                    ).use { it.ledgerApiVersion() }
                }
                "Ledger API version: $version"
            } catch (e: Exception) {
                "Could not reach ledger: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
