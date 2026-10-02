package io.github.vafu.iroh.bluetooth

import io.github.vafu.iroh.CustomTransport
import io.github.vafu.iroh.TransportAddr
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Kable-backed packet transport registered directly with Iroh. */
@OptIn(ExperimentalAtomicApi::class)
internal class BluetoothCustomTransport(
    override val id: ULong,
    remoteAddress: ByteArray,
    private val connections: StateFlow<BluetoothConnection?>,
    private val connect: (String) -> Unit = {},
) : CustomTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bound = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val clientEndpointId = AtomicReference<String?>(null)
    private val remoteAddress = remoteAddress.copyOf()

    override fun bind(endpoint: CustomTransport.Endpoint) {
        check(bound.compareAndSet(false, true)) { "Bluetooth transport is already bound" }
        val localAddress = endpoint.endpointId.value.hexToBytes()
        clientEndpointId.store(endpoint.endpointId.value)
        endpoint.setLocalAddresses(listOf(TransportAddr.Custom(id, localAddress)))
        scope.launch {
            connections.collectLatest { connection ->
                if (connection == null) return@collectLatest
                val packetChannel = connection.packets
                endpoint.notifyWritable()
                coroutineScope {
                    launch {
                        for (packet in packetChannel.rx) {
                            endpoint.offer(
                                source = TransportAddr.Custom(id, remoteAddress),
                                destination = TransportAddr.Custom(id, localAddress),
                                packet = packet,
                            )
                        }
                    }
                    launch {
                        packetChannel.writable.collect { endpoint.notifyWritable() }
                    }
                }
            }
        }
        logConnection("Bluetooth custom transport bound to Iroh")
    }

    override fun trySend(
        destination: TransportAddr.Custom,
        source: TransportAddr.Custom?,
        packet: ByteArray,
    ): Boolean {
        if (closed.load() || destination.id != id || !destination.data.contentEquals(remoteAddress)) {
            return false
        }
        if (source != null && source.id != id) return false
        clientEndpointId.load()?.let(connect)
        return connections.value?.packets?.tx?.trySend(packet)?.isSuccess == true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel(CancellationException("Bluetooth custom transport closed"))
    }
}
