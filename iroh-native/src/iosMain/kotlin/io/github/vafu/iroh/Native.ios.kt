@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vafu.iroh

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import iroh_kmp.iroh_kmp_accept_bi
import iroh_kmp.iroh_kmp_add_alpn
import iroh_kmp.iroh_kmp_await_route_change
import iroh_kmp.iroh_kmp_bind
import iroh_kmp.iroh_kmp_close_connection
import iroh_kmp.iroh_kmp_complete_address_lookup
import iroh_kmp.iroh_kmp_connect
import iroh_kmp.iroh_kmp_connection_closed
import iroh_kmp.iroh_kmp_connection_remote_id
import iroh_kmp.iroh_kmp_create
import iroh_kmp.iroh_kmp_destroy
import iroh_kmp.iroh_kmp_emit_address_lookup_result
import iroh_kmp.iroh_kmp_endpoint_data
import iroh_kmp.iroh_kmp_endpoint_id
import iroh_kmp.iroh_kmp_fail_address_lookup
import iroh_kmp.iroh_kmp_finish_send
import iroh_kmp.iroh_kmp_last_error
import iroh_kmp.iroh_kmp_network_change
import iroh_kmp.iroh_kmp_notify_custom_transport_capacity
import iroh_kmp.iroh_kmp_open_bi
import iroh_kmp.iroh_kmp_read_datagram
import iroh_kmp.iroh_kmp_read_exact
import iroh_kmp.iroh_kmp_read_to_end
import iroh_kmp.iroh_kmp_register_address_lookup
import iroh_kmp.iroh_kmp_register_custom_transport
import iroh_kmp.iroh_kmp_send_datagram
import iroh_kmp.iroh_kmp_send_datagram_wait
import iroh_kmp.iroh_kmp_set_custom_transport_addresses
import iroh_kmp.iroh_kmp_set_relay_urls
import iroh_kmp.iroh_kmp_submit_custom_packet
import iroh_kmp.iroh_kmp_write_all

internal actual fun nativeBindings(): NativeBindings = AppleNativeBindings()

private class AppleNativeBindings : NativeBindings {
    private val packetReferences = mutableListOf<StableRef<NativeBindings.PacketSink>>()
    private val lookupReferences = mutableListOf<StableRef<NativeBindings.AddressLookupSink>>()

    override fun create(): Long = iroh_kmp_create()
        .also { check(it != 0uL) { nativeError() } }
        .toLong()

    override fun addAlpn(handle: Long, alpn: ByteArray) {
        withPinnedBytes(alpn) { pointer, length ->
            checkStatus(iroh_kmp_add_alpn(handle.native, pointer, length))
        }
    }

    override fun setRelayUrls(handle: Long, urls: String) {
        checkStatus(iroh_kmp_set_relay_urls(handle.native, urls))
    }

    override fun registerCustomTransport(
        handle: Long,
        transportId: Long,
        sink: NativeBindings.PacketSink,
    ) {
        val reference = StableRef.create(sink)
        packetReferences += reference
        checkStatus(
            iroh_kmp_register_custom_transport(
                handle.native,
                transportId.toULong(),
                PACKET_OFFER_CALLBACK,
                reference.asCPointer(),
            ),
        )
    }

    override fun registerAddressLookup(
        handle: Long,
        lookupId: Long,
        sink: NativeBindings.AddressLookupSink,
    ) {
        val reference = StableRef.create(sink)
        lookupReferences += reference
        checkStatus(
            iroh_kmp_register_address_lookup(
                handle.native,
                lookupId.toULong(),
                ADDRESS_PUBLISH_CALLBACK,
                ADDRESS_RESOLVE_CALLBACK,
                ADDRESS_CANCEL_CALLBACK,
                reference.asCPointer(),
            ),
        )
    }

    override fun emitAddressLookupResult(
        handle: Long,
        lookupId: Long,
        requestId: Long,
        directAddresses: String,
        relayUrls: String,
        customAddresses: String,
        userData: String?,
    ) {
        checkStatus(
            iroh_kmp_emit_address_lookup_result(
                handle.native,
                lookupId.toULong(),
                requestId.toULong(),
                directAddresses,
                relayUrls,
                customAddresses,
                userData,
            ),
        )
    }

    override fun completeAddressLookup(handle: Long, lookupId: Long, requestId: Long) {
        checkStatus(
            iroh_kmp_complete_address_lookup(
                handle.native,
                lookupId.toULong(),
                requestId.toULong(),
            ),
        )
    }

    override fun failAddressLookup(
        handle: Long,
        lookupId: Long,
        requestId: Long,
        message: String,
    ) {
        checkStatus(
            iroh_kmp_fail_address_lookup(
                handle.native,
                lookupId.toULong(),
                requestId.toULong(),
                message,
            ),
        )
    }

    override fun setCustomTransportAddresses(
        handle: Long,
        transportId: Long,
        addresses: String,
    ) {
        checkStatus(
            iroh_kmp_set_custom_transport_addresses(
                handle.native,
                transportId.toULong(),
                addresses,
            ),
        )
    }

    override fun bind(handle: Long) {
        checkStatus(iroh_kmp_bind(handle.native))
    }

    override fun destroy(handle: Long) {
        try {
            checkStatus(iroh_kmp_destroy(handle.native))
        } finally {
            disposeCallbacks()
        }
    }

    override fun endpointId(handle: Long): String = receiveBytes { callback, context ->
        iroh_kmp_endpoint_id(handle.native, callback, context)
    }.decodeToString()

    override fun endpointData(handle: Long): String {
        val output = StringOutput()
        val reference = StableRef.create(output)
        try {
            checkStatus(
                iroh_kmp_endpoint_data(
                    handle.native,
                    ENDPOINT_DATA_CALLBACK,
                    reference.asCPointer(),
                ),
            )
            return checkNotNull(output.value)
        } finally {
            reference.dispose()
        }
    }

    override fun networkChange(handle: Long) {
        checkStatus(iroh_kmp_network_change(handle.native))
    }

    override fun connect(
        handle: Long,
        endpointId: String,
        directAddresses: String,
        relayUrls: String,
        customAddresses: String,
        alpn: ByteArray,
    ): Long {
        val connection = withPinnedBytes(alpn) { alpnPointer, alpnLength ->
            iroh_kmp_connect(
                handle.native,
                endpointId,
                directAddresses,
                relayUrls,
                customAddresses,
                alpnPointer,
                alpnLength,
            )
        }
        check(connection != 0uL) { nativeError() }
        return connection.toLong()
    }

    override fun connectionRemoteId(handle: Long, connection: Long): String =
        receiveBytes { callback, context ->
            iroh_kmp_connection_remote_id(
                handle.native,
                connection.native,
                callback,
                context,
            )
        }.decodeToString()

    override fun openBi(handle: Long, connection: Long): LongArray =
        streamHandles(iroh_kmp_open_bi(handle.native, connection.native))

    override fun acceptBi(handle: Long, connection: Long): LongArray =
        streamHandles(iroh_kmp_accept_bi(handle.native, connection.native))

    override fun sendDatagram(handle: Long, connection: Long, data: ByteArray) {
        withPinnedBytes(data) { pointer, length ->
            checkStatus(
                iroh_kmp_send_datagram(
                    handle.native,
                    connection.native,
                    pointer,
                    length,
                ),
            )
        }
    }

    override fun sendDatagramWait(handle: Long, connection: Long, data: ByteArray) {
        withPinnedBytes(data) { pointer, length ->
            checkStatus(
                iroh_kmp_send_datagram_wait(
                    handle.native,
                    connection.native,
                    pointer,
                    length,
                ),
            )
        }
    }

    override fun readDatagram(handle: Long, connection: Long): ByteArray =
        receiveBytes { callback, context ->
            iroh_kmp_read_datagram(
                handle.native,
                connection.native,
                callback,
                context,
            )
        }

    override fun connectionClosed(handle: Long, connection: Long): String =
        receiveBytes { callback, context ->
            iroh_kmp_connection_closed(
                handle.native,
                connection.native,
                callback,
                context,
            )
        }.decodeToString()

    override fun closeConnection(
        handle: Long,
        connection: Long,
        errorCode: Long,
        reason: ByteArray,
    ) {
        withPinnedBytes(reason) { pointer, length ->
            checkStatus(
                iroh_kmp_close_connection(
                    handle.native,
                    connection.native,
                    errorCode.toULong(),
                    pointer,
                    length,
                ),
            )
        }
    }

    override fun awaitRouteChange(handle: Long, connection: Long, previous: String): String =
        receiveBytes { callback, context ->
            iroh_kmp_await_route_change(
                handle.native,
                connection.native,
                previous,
                callback,
                context,
            )
        }.decodeToString()

    override fun writeAll(handle: Long, stream: Long, data: ByteArray) {
        withPinnedBytes(data) { pointer, length ->
            checkStatus(
                iroh_kmp_write_all(handle.native, stream.native, pointer, length),
            )
        }
    }

    override fun finishSend(handle: Long, stream: Long) {
        checkStatus(iroh_kmp_finish_send(handle.native, stream.native))
    }

    override fun readExact(handle: Long, stream: Long, length: Int): ByteArray =
        receiveBytes { callback, context ->
            iroh_kmp_read_exact(
                handle.native,
                stream.native,
                length.convert(),
                callback,
                context,
            )
        }

    override fun readToEnd(handle: Long, stream: Long, sizeLimit: Int): ByteArray =
        receiveBytes { callback, context ->
            iroh_kmp_read_to_end(
                handle.native,
                stream.native,
                sizeLimit.convert(),
                callback,
                context,
            )
        }

    override fun notifyCustomTransportCapacity(handle: Long, transportId: Long) {
        checkStatus(
            iroh_kmp_notify_custom_transport_capacity(
                handle.native,
                transportId.toULong(),
            ),
        )
    }

    override fun submitCustomPacket(
        handle: Long,
        transportId: Long,
        source: ByteArray,
        destination: ByteArray?,
        bytes: ByteArray,
    ) {
        withPinnedBytes(source) { sourcePointer, sourceLength ->
            withOptionalPinnedBytes(destination) { destinationPointer, destinationLength ->
                withPinnedBytes(bytes) { packetPointer, packetLength ->
                    checkStatus(
                        iroh_kmp_submit_custom_packet(
                            handle.native,
                            transportId.toULong(),
                            sourcePointer,
                            sourceLength,
                            destinationPointer,
                            destinationLength,
                            packetPointer,
                            packetLength,
                        ),
                    )
                }
            }
        }
    }

    private fun streamHandles(
        pair: CValue<iroh_kmp.iroh_kmp_stream_pair>,
    ): LongArray = pair.useContents {
        check(send != 0uL && receive != 0uL) { nativeError() }
        longArrayOf(send.toLong(), receive.toLong())
    }

    private fun disposeCallbacks() {
        packetReferences.forEach(StableRef<NativeBindings.PacketSink>::dispose)
        packetReferences.clear()
        lookupReferences.forEach(StableRef<NativeBindings.AddressLookupSink>::dispose)
        lookupReferences.clear()
    }
}

private class ByteOutput(var bytes: ByteArray? = null)
private class StringOutput(var value: String? = null)

private val BYTE_CALLBACK = staticCFunction {
        bytes: CPointer<UByteVar>?,
        length: ULong,
        context: COpaquePointer?,
    ->
    checkNotNull(context).asStableRef<ByteOutput>().get().bytes =
        bytes?.readBytes(length.toInt()) ?: ByteArray(0)
}

private val ENDPOINT_DATA_CALLBACK = staticCFunction {
        directAddresses: CPointer<kotlinx.cinterop.ByteVar>?,
        relayUrls: CPointer<kotlinx.cinterop.ByteVar>?,
        customAddresses: CPointer<kotlinx.cinterop.ByteVar>?,
        _userData: CPointer<kotlinx.cinterop.ByteVar>?,
        context: COpaquePointer?,
    ->
    checkNotNull(context).asStableRef<StringOutput>().get().value = buildString {
        append(directAddresses?.toKString().orEmpty())
        append(ENDPOINT_DATA_SEPARATOR)
        append(relayUrls?.toKString().orEmpty())
        append(ENDPOINT_DATA_SEPARATOR)
        append(customAddresses?.toKString().orEmpty())
    }
}

private val PACKET_OFFER_CALLBACK = staticCFunction {
        destination: CPointer<UByteVar>?,
        destinationLength: ULong,
        source: CPointer<UByteVar>?,
        sourceLength: ULong,
        packet: CPointer<UByteVar>?,
        packetLength: ULong,
        context: COpaquePointer?,
    ->
    val sink = checkNotNull(context).asStableRef<NativeBindings.PacketSink>().get()
    if (
        sink.offer(
            destination = destination?.readBytes(destinationLength.toInt()) ?: ByteArray(0),
            source = source?.readBytes(sourceLength.toInt()),
            packet = packet?.readBytes(packetLength.toInt()) ?: ByteArray(0),
        )
    ) 1 else 0
}

private val ADDRESS_PUBLISH_CALLBACK = staticCFunction {
        directAddresses: CPointer<kotlinx.cinterop.ByteVar>?,
        relayUrls: CPointer<kotlinx.cinterop.ByteVar>?,
        customAddresses: CPointer<kotlinx.cinterop.ByteVar>?,
        userData: CPointer<kotlinx.cinterop.ByteVar>?,
        context: COpaquePointer?,
    ->
    checkNotNull(context).asStableRef<NativeBindings.AddressLookupSink>().get().publish(
        directAddresses?.toKString().orEmpty(),
        relayUrls?.toKString().orEmpty(),
        customAddresses?.toKString().orEmpty(),
        userData?.toKString(),
    )
}

private val ADDRESS_RESOLVE_CALLBACK = staticCFunction {
        requestId: ULong,
        endpointId: CPointer<UByteVar>?,
        endpointIdLength: ULong,
        context: COpaquePointer?,
    ->
    val id = endpointId?.readBytes(endpointIdLength.toInt())?.decodeToString().orEmpty()
    if (
        checkNotNull(context)
            .asStableRef<NativeBindings.AddressLookupSink>()
            .get()
            .startResolve(requestId.toLong(), id)
    ) 1 else 0
}

private val ADDRESS_CANCEL_CALLBACK = staticCFunction {
        requestId: ULong,
        context: COpaquePointer?,
    ->
    checkNotNull(context)
        .asStableRef<NativeBindings.AddressLookupSink>()
        .get()
        .cancelResolve(requestId.toLong())
}

private fun receiveBytes(
    receive: (
        CPointer<kotlinx.cinterop.CFunction<(CPointer<UByteVar>?, ULong, COpaquePointer?) -> Unit>>?,
        COpaquePointer?,
    ) -> Int,
): ByteArray {
    val output = ByteOutput()
    val reference = StableRef.create(output)
    try {
        checkStatus(receive(BYTE_CALLBACK, reference.asCPointer()))
        return checkNotNull(output.bytes)
    } finally {
        reference.dispose()
    }
}

private fun checkStatus(status: Int) {
    check(status == 0) { nativeError(status) }
}

private fun nativeError(status: Int = -1): String =
    iroh_kmp_last_error()?.toKString()?.takeIf(String::isNotEmpty)
        ?: "Native Iroh operation failed ($status)"

private inline fun <T> withPinnedBytes(
    bytes: ByteArray,
    block: (CPointer<UByteVar>?, ULong) -> T,
): T {
    if (bytes.isEmpty()) return block(null, 0uL)
    return bytes.usePinned { pinned ->
        block(pinned.addressOf(0).reinterpret(), bytes.size.convert())
    }
}

private inline fun <T> withOptionalPinnedBytes(
    bytes: ByteArray?,
    block: (CPointer<UByteVar>?, ULong) -> T,
): T = if (bytes == null) block(null, 0uL) else withPinnedBytes(bytes, block)

private val Long.native: ULong
    get() = toULong()

private const val ENDPOINT_DATA_SEPARATOR = "\u001e"
