package io.github.vafu.iroh.bluetooth

import android.bluetooth.le.ScanSettings
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.ScannerBuilder
import kotlin.coroutines.cancellation.CancellationException

internal actual fun PeripheralBuilder.configureForBluetooth() {
    onServicesDiscovered {
        try {
            requestMtu(PREFERRED_MTU)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The connection remains valid at the default ATT payload size.
        }
    }
}

internal actual fun restoredPeripheral(
    identifier: String,
    builderAction: PeripheralBuilder.() -> Unit,
): Peripheral = Peripheral(identifier, builderAction)

@OptIn(ObsoleteKableApi::class)
internal actual fun ScannerBuilder.configureForBluetoothScan() {
    scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
}

private const val PREFERRED_MTU: Int = 517
