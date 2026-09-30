package io.github.vafu.iroh

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal fun Endpoint.Alpn.encode(): ByteArray = value.encodeToByteArray()

@OptIn(ExperimentalAtomicApi::class)
internal class EndpointImpl private constructor(
    private val native: NativeBindings,
    private val handle: Long,
    override val id: Endpoint.Id,
    private val transports: List<CustomTransportBridge>,
    private val lookups: List<AddressLookupBridge>,
) : Endpoint {
    private val closed = AtomicBoolean(false)
    override fun addr(): Endpoint.Addr {
        val groups = native.endpointData(handle).split(ENDPOINT_DATA_SEPARATOR, limit = 3)
        check(groups.size == 3) { "Native Iroh returned invalid endpoint data" }
        return Endpoint.Addr(
            id = id,
            addrs = transportAddressesFromNative(groups[0], groups[1], groups[2]),
        )
    }

    override suspend fun connect(endpoint: Endpoint.Addr, alpn: Endpoint.Alpn): Connection =
        nativeCall {
            val record = AddressLookup.Record(endpoint.addrs)
            val connection = native.connect(
                handle = handle,
                endpointId = endpoint.id.value,
                directAddresses = record.directAddresses.joinToString("\n"),
                relayUrls = record.relayUrls.joinToString("\n"),
                customAddresses = record.customAddresses.toNativeLines(),
                alpn = alpn.encode(),
            )
            ConnectionImpl(native, handle, connection)
        }

    override suspend fun networkChange() {
        nativeCall { native.networkChange(handle) }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        transports.forEach { runCatching(it::close) }
        lookups.forEach(AddressLookupBridge::close)
        // Native destruction is the final ownership boundary. Kotlin bridges
        // have already stopped producing callbacks for this handle.
        nativeCall { native.destroy(handle) }
    }

    companion object {
        suspend fun create(
            native: NativeBindings,
            alpns: List<Endpoint.Alpn>,
            relayMode: RelayMode,
            customTransports: List<CustomTransport>,
            addressLookups: List<AddressLookup>,
        ): EndpointImpl {
            requireValidTransports(customTransports)
            val handle = nativeCall {
                native.create().also { check(it != 0L) { "Failed to create native Iroh endpoint" } }
            }
            var transports = emptyList<CustomTransportBridge>()
            var lookups = emptyList<AddressLookupBridge>()
            try {
                val id = nativeCall { Endpoint.Id(native.endpointId(handle)) }
                transports = customTransports.map { transport ->
                    CustomTransportBridge(transport, native, handle, id)
                }
                lookups = addressLookups.mapIndexed { index, lookup ->
                    AddressLookupBridge(index.toULong() + 1uL, lookup, native, handle)
                }
                nativeCall {
                    alpns.forEach { native.addAlpn(handle, it.encode()) }
                    val relayUrls = (relayMode as? RelayMode.Custom)
                        ?.relays
                        .orEmpty()
                        .joinToString("\n", transform = TransportAddr.Relay::url)
                    native.setRelayUrls(handle, relayUrls)
                    transports.forEach { transport ->
                        native.registerCustomTransport(handle, transport.id.toLong(), transport)
                    }
                    lookups.forEach { lookup ->
                        native.registerAddressLookup(handle, lookup.id.toLong(), lookup)
                    }
                    transports.forEach(CustomTransportBridge::bind)
                    native.bind(handle)
                }
                return EndpointImpl(
                    native = native,
                    handle = handle,
                    id = id,
                    transports = transports,
                    lookups = lookups,
                )
            } catch (error: Throwable) {
                runCatching { native.destroy(handle) }
                transports.forEach { runCatching(it::close) }
                lookups.forEach(AddressLookupBridge::close)
                throw error
            }
        }
    }
}

private const val ENDPOINT_DATA_SEPARATOR = "\u001e"
