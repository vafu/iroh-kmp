package io.github.vafu.iroh.bluetooth

import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.ScannerBuilder
import kotlin.uuid.Uuid

internal actual fun PeripheralBuilder.configureForBluetooth() = Unit

internal actual fun restoredPeripheral(
    identifier: String,
    builderAction: PeripheralBuilder.() -> Unit,
): Peripheral = Peripheral(Uuid.parse(identifier), builderAction)

internal actual fun ScannerBuilder.configureForBluetoothScan() = Unit
