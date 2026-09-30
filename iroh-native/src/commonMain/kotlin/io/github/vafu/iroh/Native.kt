package io.github.vafu.iroh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thin platform mapping of the native JNI or C ABI. */
internal interface NativeBindings {
    interface PacketSink {
        fun offer(destination: ByteArray, source: ByteArray?, packet: ByteArray): Boolean
    }

    interface AddressLookupSink {
        fun publish(
            directAddresses: String,
            relayUrls: String,
            customAddresses: String,
            userData: String?,
        )

        fun startResolve(requestId: Long, endpointId: String): Boolean

        fun cancelResolve(requestId: Long)
    }

    fun create(): Long

    fun addAlpn(handle: Long, alpn: ByteArray)

    fun setRelayUrls(handle: Long, urls: String)

    fun registerCustomTransport(handle: Long, transportId: Long, sink: PacketSink)

    fun registerAddressLookup(handle: Long, lookupId: Long, sink: AddressLookupSink)

    fun emitAddressLookupResult(
        handle: Long,
        lookupId: Long,
        requestId: Long,
        directAddresses: String,
        relayUrls: String,
        customAddresses: String,
        userData: String?,
    )

    fun completeAddressLookup(handle: Long, lookupId: Long, requestId: Long)

    fun failAddressLookup(handle: Long, lookupId: Long, requestId: Long, message: String)

    fun setCustomTransportAddresses(handle: Long, transportId: Long, addresses: String)

    fun bind(handle: Long)

    fun destroy(handle: Long)

    fun endpointId(handle: Long): String

    fun endpointData(handle: Long): String

    fun networkChange(handle: Long)

    fun connect(
        handle: Long,
        endpointId: String,
        directAddresses: String,
        relayUrls: String,
        customAddresses: String,
        alpn: ByteArray,
    ): Long

    fun connectionRemoteId(handle: Long, connection: Long): String

    fun openBi(handle: Long, connection: Long): LongArray

    fun acceptBi(handle: Long, connection: Long): LongArray

    fun sendDatagram(handle: Long, connection: Long, data: ByteArray)

    fun sendDatagramWait(handle: Long, connection: Long, data: ByteArray)

    fun readDatagram(handle: Long, connection: Long): ByteArray

    fun connectionClosed(handle: Long, connection: Long): String

    fun closeConnection(handle: Long, connection: Long, errorCode: Long, reason: ByteArray)

    fun awaitRouteChange(handle: Long, connection: Long, previous: String): String

    fun writeAll(handle: Long, stream: Long, data: ByteArray)

    fun finishSend(handle: Long, stream: Long)

    fun readExact(handle: Long, stream: Long, length: Int): ByteArray

    fun readToEnd(handle: Long, stream: Long, sizeLimit: Int): ByteArray

    fun notifyCustomTransportCapacity(handle: Long, transportId: Long)

    fun submitCustomPacket(
        handle: Long,
        transportId: Long,
        source: ByteArray,
        destination: ByteArray?,
        bytes: ByteArray,
    )
}

internal expect fun nativeBindings(): NativeBindings

internal suspend inline fun <T> nativeCall(crossinline operation: () -> T): T =
    withContext(Dispatchers.Default) { operation() }
