package io.github.vafu.iroh

internal class CustomTransportBridge(
    private val transport: CustomTransport,
    private val native: NativeBindings,
    private val endpoint: Long,
    override val endpointId: Endpoint.Id,
) : NativeBindings.PacketSink, CustomTransport.Endpoint {
    val id: ULong = transport.id

    fun bind() {
        transport.bind(this)
    }

    override fun offer(
        destination: ByteArray,
        source: ByteArray?,
        packet: ByteArray,
    ): Boolean = transport.trySend(
        destination = TransportAddr.Custom(id, destination),
        source = source?.let { TransportAddr.Custom(id, it) },
        packet = packet,
    )

    override fun setLocalAddresses(addresses: List<TransportAddr.Custom>) {
        require(addresses.all { it.id == id }) { "Custom address belongs to a different transport" }
        native.setCustomTransportAddresses(
            endpoint,
            id.toLong(),
            addresses.map(TransportAddr.Custom::data).toHexLines(),
        )
    }

    override fun offer(
        source: TransportAddr.Custom,
        destination: TransportAddr.Custom?,
        packet: ByteArray,
    ): Boolean = runCatching {
        require(source.id == id && (destination == null || destination.id == id)) {
            "Custom address belongs to a different transport"
        }
        native.submitCustomPacket(
            endpoint,
            id.toLong(),
            source.data,
            destination?.data,
            packet,
        )
    }.isSuccess

    override fun notifyWritable() {
        // A queued platform write may finish concurrently with endpoint teardown.
        // Once native ownership is gone, that final capacity notification is stale.
        runCatching { native.notifyCustomTransportCapacity(endpoint, id.toLong()) }
    }

    fun close() {
        transport.close()
    }
}
