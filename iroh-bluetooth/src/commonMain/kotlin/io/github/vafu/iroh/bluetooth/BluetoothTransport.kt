package io.github.vafu.iroh.bluetooth

import io.github.vafu.iroh.AddressLookup
import io.github.vafu.iroh.CustomTransport
import io.github.vafu.iroh.Endpoint
import io.github.vafu.iroh.TransportAddr
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class DefaultBluetoothTransport(
    private val options: BluetoothTransportOptions,
) : BluetoothTransport {
    private val manager = BluetoothConnectionManager(options)
    private val packetTransport = BluetoothCustomTransport(
        transportId = options.transportId,
        remoteAddress = options.remoteAddress,
        connections = manager.connections,
        connect = manager::start,
    )

    override val id: ULong = options.transportId

    override val peripheralId: String?
        get() = manager.peripheralId()

    override fun bind(endpoint: CustomTransport.Endpoint) = packetTransport.bind(endpoint)

    override fun trySend(
        destination: TransportAddr.Custom,
        source: TransportAddr.Custom?,
        packet: ByteArray,
    ): Boolean = packetTransport.trySend(destination, source, packet)

    override fun resolve(endpointId: Endpoint.Id): Flow<AddressLookup.Record>? =
        manager.addressRecords.distinctUntilChanged()
            .takeIf { endpointId == options.remoteEndpointId }

    override fun pause() = manager.pause()

    override fun resume() = manager.resume()

    override fun close() {
        packetTransport.close()
        manager.close()
    }

    override suspend fun closeAndWait() {
        packetTransport.close()
        manager.closeAndWait()
    }
}
