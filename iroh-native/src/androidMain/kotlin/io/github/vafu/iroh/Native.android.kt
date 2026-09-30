package io.github.vafu.iroh

import android.content.Context

internal actual fun nativeBindings(): NativeBindings = AndroidNativeBindings

internal object AndroidNativeBindings : NativeBindings {
    init {
        System.loadLibrary("iroh_kmp_kmp")
    }

    external fun installAndroidContext(context: Context)
    external override fun create(): Long
    external override fun addAlpn(handle: Long, alpn: ByteArray)
    external override fun setRelayUrls(handle: Long, urls: String)

    external override fun registerCustomTransport(
        handle: Long,
        transportId: Long,
        sink: NativeBindings.PacketSink,
    )

    external override fun registerAddressLookup(
        handle: Long,
        lookupId: Long,
        sink: NativeBindings.AddressLookupSink,
    )

    external override fun emitAddressLookupResult(
        handle: Long,
        lookupId: Long,
        requestId: Long,
        directAddresses: String,
        relayUrls: String,
        customAddresses: String,
        userData: String?,
    )

    external override fun completeAddressLookup(handle: Long, lookupId: Long, requestId: Long)

    external override fun failAddressLookup(
        handle: Long,
        lookupId: Long,
        requestId: Long,
        message: String,
    )

    external override fun setCustomTransportAddresses(
        handle: Long,
        transportId: Long,
        addresses: String,
    )

    external override fun bind(handle: Long)
    external override fun destroy(handle: Long)
    external override fun endpointId(handle: Long): String
    external override fun endpointData(handle: Long): String
    external override fun networkChange(handle: Long)

    external override fun connect(
        handle: Long,
        endpointId: String,
        directAddresses: String,
        relayUrls: String,
        customAddresses: String,
        alpn: ByteArray,
    ): Long

    external override fun connectionRemoteId(handle: Long, connection: Long): String
    external override fun openBi(handle: Long, connection: Long): LongArray
    external override fun acceptBi(handle: Long, connection: Long): LongArray
    external override fun sendDatagram(handle: Long, connection: Long, data: ByteArray)
    external override fun sendDatagramWait(handle: Long, connection: Long, data: ByteArray)
    external override fun readDatagram(handle: Long, connection: Long): ByteArray
    external override fun connectionClosed(handle: Long, connection: Long): String

    external override fun closeConnection(
        handle: Long,
        connection: Long,
        errorCode: Long,
        reason: ByteArray,
    )

    external override fun awaitRouteChange(handle: Long, connection: Long, previous: String): String
    external override fun writeAll(handle: Long, stream: Long, data: ByteArray)
    external override fun finishSend(handle: Long, stream: Long)
    external override fun readExact(handle: Long, stream: Long, length: Int): ByteArray
    external override fun readToEnd(handle: Long, stream: Long, sizeLimit: Int): ByteArray
    external override fun notifyCustomTransportCapacity(handle: Long, transportId: Long)

    external override fun submitCustomPacket(
        handle: Long,
        transportId: Long,
        source: ByteArray,
        destination: ByteArray?,
        bytes: ByteArray,
    )
}
