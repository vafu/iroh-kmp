package io.github.vafu.iroh.bluetooth

import io.github.vafu.iroh.AddressLookup
import io.github.vafu.iroh.Endpoint
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the platform GATT lifecycle independently from pairing or address lookup. */
@OptIn(ExperimentalAtomicApi::class)
internal class BluetoothConnectionManager(
    private val options: BluetoothTransportOptions,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val currentPeripheralId = AtomicReference(options.peripheralId)
    private val enabled = MutableStateFlow(true)
    private val mutableConnections = MutableStateFlow<BluetoothConnection?>(null)
    private val mutableAddressRecords = MutableSharedFlow<AddressLookup.Record>(replay = 1)
    private val maintenanceFinished = CompletableDeferred<Unit>()

    val connections: StateFlow<BluetoothConnection?> = mutableConnections.asStateFlow()
    val addressRecords: Flow<AddressLookup.Record> = mutableAddressRecords

    fun start(clientEndpointId: String) {
        if (!started.compareAndSet(false, true)) return
        logConnection("Bluetooth connection manager started")
        val maintenance = scope.launch { maintain(clientEndpointId) }
        maintenance.invokeOnCompletion { maintenanceFinished.complete(Unit) }
    }

    fun peripheralId(): String? = currentPeripheralId.load()

    fun pause() {
        if (!enabled.value) return
        logConnection("Bluetooth connection manager paused")
        enabled.value = false
    }

    fun resume() {
        if (closed.load() || enabled.value) return
        logConnection("Bluetooth connection manager resumed")
        enabled.value = true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        if (!started.load()) maintenanceFinished.complete(Unit)
    }

    suspend fun closeAndWait() {
        close()
        maintenanceFinished.await()
    }

    private suspend fun maintain(clientEndpointId: String) {
        enabled.collectLatest { isEnabled ->
            if (isEnabled) maintainWhileEnabled(clientEndpointId)
        }
    }

    private suspend fun maintainWhileEnabled(clientEndpointId: String) {
        while (scope.isActive) {
            var connection: BluetoothConnection? = null
            try {
                awaitBluetoothAvailable(options.statusProvider)
                connection = openBluetoothConnection(
                    options.copy(peripheralId = currentPeripheralId.load()),
                )
                val connectedPeripheralId = connection.peripheralId
                if (currentPeripheralId.exchange(connectedPeripheralId) != connectedPeripheralId) {
                    runCatching { options.onPeripheralId(connectedPeripheralId) }
                        .onFailure { error ->
                            logConnection(
                                "Failed to retain Bluetooth peripheral identifier: ${error.message}",
                            )
                        }
                }
                val remoteEndpoint = options.authenticator.authenticate(
                    connection,
                    Endpoint.Id(clientEndpointId),
                )
                require(remoteEndpoint.id == options.remoteEndpointId) {
                    "Bluetooth peer authenticated as an unexpected Iroh endpoint"
                }
                checkNotNull(connection.packets) { "Bluetooth packet channel is unavailable" }
                mutableAddressRecords.emit(AddressLookup.Record(remoteEndpoint.addrs))
                mutableConnections.value = connection
                logConnection("Bluetooth connection authenticated")
                connection.awaitClosed()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logConnection(
                    "Bluetooth connection failed: " +
                        (error.message ?: error::class.simpleName ?: "unknown error"),
                )
            } finally {
                mutableConnections.value = null
                withContext(NonCancellable) { connection?.closeAndWait() }
            }
        }
    }
}

private suspend fun awaitBluetoothAvailable(statusProvider: BluetoothStatusProvider) {
    logConnection("waiting for Bluetooth availability")
    statusProvider.updates().first { it == BluetoothStatus.Available }
    logConnection("Bluetooth is available")
}
