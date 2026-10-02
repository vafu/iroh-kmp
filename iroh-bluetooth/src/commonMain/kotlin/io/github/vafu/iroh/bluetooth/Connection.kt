package io.github.vafu.iroh.bluetooth

import com.juul.kable.Advertisement
import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.Scanner
import com.juul.kable.ScannerBuilder
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface ConnectionChannel {
    val rx: ReceiveChannel<ByteArray>
    val tx: SendChannel<ByteArray>
}

internal interface CapacityConnectionChannel : ConnectionChannel {
    val writable: Flow<Unit>
}

/** One physical Kable connection. */
internal interface BluetoothConnection : BluetoothLink {
    val control: ConnectionChannel
    val packets: CapacityConnectionChannel?

    /** Waits until every control message currently in [control] has reached Kable. */
    suspend fun awaitClosed()
    fun close()
    suspend fun closeAndWait()
}

internal suspend fun openBluetoothConnection(
    options: BluetoothTransportOptions,
): BluetoothConnection = openKableBluetoothConnection(
    options = options,
)

internal expect fun restoredPeripheral(
    identifier: String,
    builderAction: PeripheralBuilder.() -> Unit,
): Peripheral

internal fun scannedPeripheral(
    advertisement: Advertisement,
    builderAction: PeripheralBuilder.() -> Unit,
): Peripheral = Peripheral(advertisement, builderAction)

internal expect fun PeripheralBuilder.configureForBluetooth()
internal expect fun ScannerBuilder.configureForBluetoothScan()

internal const val MAX_BLUETOOTH_MESSAGE_SIZE: Int = 64 * 1024 + 64

internal suspend fun openKableBluetoothConnection(
    options: BluetoothTransportOptions,
): BluetoothConnection = coroutineScope {
    val serviceUuid = Uuid.parse(options.serviceUuid)
    if (options.peripheralId == null) {
        return@coroutineScope connectScanned(serviceUuid, options)
    }

    val direct = async {
        connectRestoredResult(options.peripheralId, serviceUuid, options)
    }
    val scanned = async { scanResult(serviceUuid, options) }

    select {
        direct.onAwait { result ->
            result.fold(
                onSuccess = { connection ->
                    scanned.cancelAndJoin()
                    connection
                },
                onFailure = {
                    val advertisement = scanned.await().getOrThrow()
                    connect(
                        scannedPeripheral(advertisement) { configureForBluetooth() },
                        serviceUuid,
                        options,
                    )
                },
            )
        }
        scanned.onAwait { result ->
            result.fold(
                onSuccess = { advertisement ->
                    closeConnectionAttempt(direct)
                    connect(
                        scannedPeripheral(advertisement) { configureForBluetooth() },
                        serviceUuid,
                        options,
                    )
                },
                onFailure = { direct.await().getOrThrow() },
            )
        }
    }
}

private suspend fun closeConnectionAttempt(
    attempt: kotlinx.coroutines.Deferred<Result<BluetoothConnection>>,
) {
    attempt.cancel()
    try {
        attempt.await().getOrNull()?.closeAndWait()
    } catch (_: CancellationException) {
        attempt.cancelAndJoin()
    }
}

private suspend fun connectScanned(
    serviceUuid: Uuid,
    options: BluetoothTransportOptions,
): BluetoothConnection {
    val advertisement = scan(serviceUuid, options)
    return connect(
        scannedPeripheral(advertisement) { configureForBluetooth() },
        serviceUuid,
        options,
    )
}

private suspend fun scan(
    serviceUuid: Uuid,
    options: BluetoothTransportOptions,
): com.juul.kable.Advertisement {
    val started = TimeSource.Monotonic.markNow()
    logConnection("Bluetooth scan started")
    val advertisement = withTimeoutOrNull(options.scanTimeout) {
        Scanner {
            configureForBluetoothScan()
            filters { match { services = listOf(serviceUuid) } }
        }.advertisements.first()
    } ?: throw BluetoothOperationTimedOut("scan", options.scanTimeout)
    logConnection("Bluetooth advertisement found in ${started.elapsedNow().inWholeMilliseconds}ms")
    return advertisement
}

private suspend fun connectRestoredResult(
    peripheralId: String,
    serviceUuid: Uuid,
    options: BluetoothTransportOptions,
): Result<BluetoothConnection> = try {
    Result.success(
        connect(
            restoredPeripheral(peripheralId) { configureForBluetooth() },
            serviceUuid,
            options,
        ),
    )
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}

private suspend fun scanResult(
    serviceUuid: Uuid,
    options: BluetoothTransportOptions,
): Result<com.juul.kable.Advertisement> = try {
    Result.success(scan(serviceUuid, options))
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}

private suspend fun connect(
    peripheral: Peripheral,
    serviceUuid: Uuid,
    options: BluetoothTransportOptions,
): BluetoothConnection {
    val connection = KableBluetoothConnection(peripheral, serviceUuid, options)
    return try {
        connection.connect()
        connection
    } catch (error: Throwable) {
        withContext(NonCancellable) { connection.closeAndWait() }
        throw error
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class KableBluetoothConnection(
    private val peripheral: Peripheral,
    serviceUuid: Uuid,
    private val options: BluetoothTransportOptions,
) : BluetoothConnection {
    private val controlCharacteristic = characteristicOf(
        service = serviceUuid,
        characteristic = Uuid.parse(options.controlCharacteristicUuid),
    )
    private val packetCharacteristic = characteristicOf(
        service = serviceUuid,
        characteristic = Uuid.parse(options.packetCharacteristicUuid),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controlChunks = Channel<ByteArray>(Channel.BUFFERED)
    private val controlRx = Channel<ByteArray>(Channel.BUFFERED)
    private val controlTx = Channel<ByteArray>(Channel.BUFFERED)
    private val packetRx = packetCharacteristic?.let { Channel<ByteArray>(PACKET_QUEUE_CAPACITY) }
    private val packetTx = packetCharacteristic?.let { Channel<ByteArray>(PACKET_QUEUE_CAPACITY) }
    private val packetWritable = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val closed = AtomicBoolean(false)
    private val closedSignal = CompletableDeferred<Unit>()
    private val cleanupFinished = CompletableDeferred<Unit>()
    private val controlFlushed = CompletableDeferred<Unit>()
    private val flushMarker = ByteArray(0)
    private val failure = AtomicReference<Throwable?>(null)
    private val controlReader = LengthPrefixedMessageReader(
        MAX_BLUETOOTH_MESSAGE_SIZE,
        "Bluetooth control message",
        controlChunks::receive,
    )
    private val packetDecoder = LengthPrefixedMessageDecoder(
        MAX_BLUETOOTH_MESSAGE_SIZE,
        "Bluetooth packet",
    )

    @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
    override val peripheralId: String = peripheral.identifier.toString()

    override val control: ConnectionChannel = object : ConnectionChannel {
        override val rx = controlRx
        override val tx = controlTx
    }

    override suspend fun sendControl(message: ByteArray) = controlTx.send(message)

    override suspend fun receiveControl(): ByteArray = controlRx.receive()

    override val packets: CapacityConnectionChannel? = packetRx?.let { incoming ->
        val outgoing = checkNotNull(packetTx)
        object : CapacityConnectionChannel {
            override val rx = incoming
            override val tx = outgoing
            override val writable = packetWritable
        }
    }

    suspend fun connect() {
        check(!closed.load()) { "Bluetooth connection is closed" }
        startIo()
        val started = TimeSource.Monotonic.markNow()
        logConnection("Bluetooth GATT connect started ($peripheralId)")
        try {
            val connectionScope = withTimeoutOrNull(options.connectTimeout) {
                peripheral.connect()
            } ?: throw BluetoothOperationTimedOut("GATT connection", options.connectTimeout)
            logConnection("Bluetooth GATT ready in ${started.elapsedNow().inWholeMilliseconds}ms")
            connectionScope.coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (!closed.load()) {
                    fail("connection lifecycle", cause ?: IllegalStateException("Bluetooth connection closed"))
                }
            }
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (error: Throwable) {
            fail("connect", error)
            throw error
        }
    }

    override suspend fun flushControl() {
        check(!controlFlushed.isCompleted) { "Bluetooth control channel was already flushed" }
        controlTx.send(flushMarker)
        controlFlushed.await()
    }

    override suspend fun awaitClosed() {
        closedSignal.await()
    }

    override fun close() {
        beginClose()
    }

    override suspend fun closeAndWait() {
        beginClose()
        cleanupFinished.await()
    }

    private fun startIo() {
        peripheral.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                peripheral.state
                    .dropWhile { it !is State.Connected }
                    .filterIsInstance<State.Disconnected>()
                    .first()
                if (!closed.load()) fail("connection state", IllegalStateException("Bluetooth connection lost"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!closed.load()) fail("connection state", error)
            }
        }
        observe(controlCharacteristic, controlChunks)
        packetCharacteristic?.let(::observePackets)
        scope.launch {
            try {
                while (true) controlRx.send(controlReader.receive())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!closed.load()) fail("control decoder", error)
            }
        }
        scope.launch { writeLoop(controlTx, controlCharacteristic, WriteType.WithResponse, false) }
        if (packetCharacteristic != null && packetTx != null) {
            scope.launch { writeLoop(packetTx, packetCharacteristic, WriteType.WithoutResponse, true) }
        }
    }

    private fun observe(characteristic: Characteristic, incoming: Channel<ByteArray>) {
        peripheral.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                peripheral.observe(characteristic).collect(incoming::send)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!closed.load()) fail("control observer", error)
            }
        }
    }

    private fun observePackets(characteristic: Characteristic) {
        val incoming = checkNotNull(packetRx)
        peripheral.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                peripheral.observe(characteristic).collect { chunk ->
                    packetDecoder.append(chunk).forEach { incoming.send(it) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!closed.load()) fail("packet observer", error)
            }
        }
    }

    private suspend fun writeLoop(
        outgoing: Channel<ByteArray>,
        characteristic: Characteristic,
        writeType: WriteType,
        notifyWritable: Boolean,
    ) {
        try {
            for (message in outgoing) {
                if (outgoing === controlTx && message === flushMarker) {
                    controlFlushed.complete(Unit)
                    continue
                }
                if (notifyWritable) packetWritable.tryEmit(Unit)
                if (outgoing === controlTx) {
                    logConnection("Bluetooth control write started (${message.size} bytes)")
                }
                writeMessage(characteristic, message, writeType)
                if (outgoing === controlTx) {
                    logConnection("Bluetooth control write completed (${message.size} bytes)")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!closed.load()) fail("writer", error)
        }
    }

    private suspend fun writeMessage(
        characteristic: Characteristic,
        message: ByteArray,
        writeType: WriteType,
    ) {
        val framed = message.withLengthPrefix(MAX_BLUETOOTH_MESSAGE_SIZE, "Bluetooth message")
        val maximumLength = peripheral
            .maximumWriteValueLengthForType(writeType)
            .coerceIn(1, MAX_GATT_ATTRIBUTE_VALUE_LENGTH)
        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + maximumLength, framed.size)
            val written = withTimeoutOrNull(options.writeTimeout) {
                peripheral.write(characteristic, framed.copyOfRange(offset, end), writeType)
                true
            }
            if (written == null) {
                throw BluetoothOperationTimedOut("GATT write", options.writeTimeout)
            }
            offset = end
        }
    }

    private fun fail(source: String, error: Throwable) {
        val first = if (failure.compareAndSet(null, error)) {
            logConnection("Bluetooth $source failed:\n${error.stackTraceToString()}")
            error
        } else {
            failure.load() ?: error
        }
        beginClose(first)
    }

    private fun beginClose(error: Throwable? = null) {
        if (!closed.compareAndSet(false, true)) return
        closeChannels(error)
        scope.cancel()
        // A disconnected peripheral is lifecycle state, not an uncaught
        // coroutine failure. Lifecycle owners only need a closure notification.
        closedSignal.complete(Unit)
        cleanupScope.launch {
            try {
                // Kable's close() only disposes this Peripheral. Its suspending
                // disconnect() is what waits until CoreBluetooth/GATT has
                // actually released the connection, which must happen before a
                // replacement Peripheral starts using the same device.
                runCatching { peripheral.disconnect() }
                    .onFailure { disconnectError ->
                        if (disconnectError !is CancellationException) {
                            logConnection(
                                "Bluetooth disconnect failed: " +
                                    (disconnectError.message
                                        ?: disconnectError::class.simpleName
                                        ?: "unknown error"),
                            )
                        }
                    }
                peripheral.close()
            } finally {
                cleanupFinished.complete(Unit)
                cleanupScope.cancel()
            }
        }
    }

    private fun closeChannels(error: Throwable? = null) {
        // These channels are observed by longer-lived transport coroutines.
        // Closing them exceptionally would leak a per-connection failure into
        // that parent scope and permanently kill Bluetooth fallback. A normal
        // close lets the manager replace this physical connection instead.
        controlChunks.close()
        controlRx.close()
        controlTx.close()
        if (error != null) controlFlushed.completeExceptionally(error)
        packetRx?.close()
        packetTx?.close()
    }
}

private const val MAX_GATT_ATTRIBUTE_VALUE_LENGTH: Int = 512

private const val PACKET_QUEUE_CAPACITY: Int = 256
private class BluetoothOperationTimedOut(
    operation: String,
    timeout: kotlin.time.Duration,
) : IllegalStateException(
    "Bluetooth $operation timed out after ${timeout.inWholeMilliseconds}ms",
)
