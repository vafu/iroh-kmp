package io.github.vafu.iroh

import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.Flow

/** Complete platform-independent configuration used to bind an [Endpoint]. */
data class EndpointOptions(
    /** Application protocols accepted by the endpoint. */
    val alpns: List<Endpoint.Alpn>,
    /** Relays explicitly available to the endpoint. */
    val relayMode: RelayMode = RelayMode.Disabled,
    /** Additional packet transports implemented outside native Iroh. */
    val customTransports: List<CustomTransport> = emptyList(),
    /** Additional services used to publish and resolve endpoint addresses. */
    val addressLookups: List<AddressLookup> = emptyList(),
)

/** Binds concrete [Endpoint] implementations for one runtime or platform. */
fun interface EndpointFactory {
    /** Creates and binds an endpoint configured by [options]. */
    suspend fun bind(options: EndpointOptions): Endpoint
}

/** One bound Iroh endpoint with its own cryptographic identity. */
interface Endpoint {
    /** Textual application protocol negotiated during the QUIC handshake. */
    @JvmInline
    value class Alpn(
        /** Protocol name encoded into the TLS ALPN extension. */
        val value: String,
    ) {
        init {
            require(value.encodeToByteArray().size in 1..255) {
                "ALPN must encode to between 1 and 255 bytes"
            }
        }

    }

    /** Cryptographic identity of an Iroh endpoint. */
    @JvmInline
    value class Id(
        /** Canonical textual endpoint identifier. */
        val value: String,
    ) {
        override fun toString(): String = value
    }

    /** Network-level addressing information for an endpoint. */
    data class Addr(
        /** Cryptographic identity of the endpoint being addressed. */
        val id: Id,
        /** Known paths through which the endpoint may be reached. */
        val addrs: List<TransportAddr> = emptyList(),
    )

    /** Identity generated for this local endpoint. */
    val id: Id

    /** Returns the local endpoint's currently advertised addresses. */
    fun addr(): Addr

    /** Establishes an encrypted connection to [endpoint] using [alpn]. */
    suspend fun connect(endpoint: Addr, alpn: Alpn): Connection

    /**
     * Notifies Iroh that local network conditions may have changed.
     *
     * Iroh refreshes its bindings and probes known paths. Duplicate notifications
     * are safe, and a closed endpoint ignores the notification.
     */
    suspend fun networkChange()

    /** Stops the endpoint and releases its native resources. */
    suspend fun close()
}

/** One network path through which an Iroh endpoint may be reached. */
sealed interface TransportAddr {
    /** Direct IP socket address understood by Iroh's UDP transport. */
    data class Ip(
        /** Socket address in Iroh's canonical textual form. */
        val address: String,
    ) : TransportAddr

    /** Explicit relay eligible to carry end-to-end encrypted traffic. */
    data class Relay(
        /** Relay URL accepted by Iroh. */
        val url: String,
    ) : TransportAddr

    /** Opaque address interpreted by the custom transport identified by [id]. */
    class Custom(
        /** Stable identifier of the custom transport implementation. */
        val id: ULong,
        /** Transport-owned address bytes. */
        val data: ByteArray,
    ) : TransportAddr {
        override fun equals(other: Any?): Boolean =
            other is Custom && id == other.id && data.contentEquals(other.data)

        override fun hashCode(): Int = 31 * id.hashCode() + data.contentHashCode()

        override fun toString(): String = "TransportAddr.Custom($id, ${data.size} bytes)"
    }
}

/** Relay configuration for a local endpoint. */
sealed interface RelayMode {
    /** Disables relay discovery and relay traffic. */
    data object Disabled : RelayMode

    /** Allows only the explicitly supplied [relays]. */
    data class Custom(
        /** Complete relay allowlist for this endpoint. */
        val relays: List<TransportAddr.Relay>,
    ) : RelayMode
}

/** One established encrypted QUIC connection between two Iroh endpoints. */
interface Connection {
    /** Cryptographic identity authenticated by the QUIC connection. */
    val remoteId: Endpoint.Id

    /** Reactive stream of routes selected by Iroh for this connection. */
    val routes: Flow<Route>

    /** Opens a locally initiated bidirectional QUIC stream. */
    suspend fun openBi(): BiStream

    /** Accepts the next remotely initiated bidirectional QUIC stream. */
    suspend fun acceptBi(): BiStream

    /** Queues an unreliable QUIC datagram without waiting for capacity. */
    fun sendDatagram(data: ByteArray)

    /** Sends an unreliable QUIC datagram after waiting for transport capacity. */
    suspend fun sendDatagramWait(data: ByteArray)

    /** Receives the next complete QUIC datagram. */
    suspend fun readDatagram(): ByteArray

    /** Waits for closure and returns Iroh's human-readable reason. */
    suspend fun closed(): String

    /** Closes the connection with an application error code and opaque reason. */
    fun close(errorCode: ULong = 0uL, reason: ByteArray = byteArrayOf())
}

/** Sending half of one QUIC stream. */
interface SendStream {
    /** Writes every byte or fails without reporting a partial successful message. */
    suspend fun writeAll(data: ByteArray)

    /** Finishes the sending half while leaving its receive half available. */
    fun finish()
}

/** Receiving half of one QUIC stream. */
interface ReceiveStream {
    /** Reads exactly [length] bytes or fails if the stream ends first. */
    suspend fun readExact(length: Int): ByteArray

    /** Reads until stream completion while rejecting data beyond [sizeLimit]. */
    suspend fun readToEnd(sizeLimit: Int): ByteArray
}

/** Both halves of one bidirectional QUIC stream. */
data class BiStream(
    /** Sending half of the stream. */
    val send: SendStream,
    /** Receiving half of the stream. */
    val receive: ReceiveStream,
)

/** Currently selected network route. */
sealed interface Route {
    /** No validated path is currently selected. */
    data object Disconnected : Route

    /** Direct UDP path between the two endpoints. */
    data object Direct : Route

    /** Explicit relay carrying end-to-end encrypted traffic. */
    data object Relay : Route

    /** Kotlin-provided custom transport identified by [transportId]. */
    data class Custom(
        /** Identifier registered by the selected custom transport. */
        val transportId: ULong,
    ) : Route
}

/** Address discovery service registered directly with native Iroh. */
interface AddressLookup {
    /** Addresses and provider metadata discovered for a known endpoint identity. */
    data class Record(
        /** Candidate paths added to the resolved endpoint. */
        val addrs: List<TransportAddr> = emptyList(),
        /** Provider-owned metadata propagated by Iroh without interpretation. */
        val userData: String? = null,
    )

    /** Publishes local endpoint information when this provider supports publication. */
    suspend fun publish(record: Record) {}

    /**
     * Resolves [endpointId], or returns `null` when this provider cannot handle it.
     * Iroh collects the returned cold flow only while it needs this lookup.
     */
    fun resolve(endpointId: Endpoint.Id): Flow<Record>?
}

/** Packet transport implemented in Kotlin and registered with native Iroh. */
interface CustomTransport {
    /** Native Iroh operations supplied to a bound custom transport. */
    interface Endpoint {
        /** Identity of the local Iroh endpoint using this transport. */
        val endpointId: io.github.vafu.iroh.Endpoint.Id

        /** Replaces the local custom addresses advertised by this transport. */
        fun setLocalAddresses(addresses: List<TransportAddr.Custom>)

        /** Offers one complete received packet to Iroh without blocking. */
        fun offer(
            source: TransportAddr.Custom,
            destination: TransportAddr.Custom?,
            packet: ByteArray,
        ): Boolean

        /** Wakes Iroh after outgoing transport capacity becomes available. */
        fun notifyWritable()
    }

    /** Stable identifier shared by every implementation of this transport. */
    val id: ULong

    /** Binds the transport to one native Iroh endpoint. */
    fun bind(endpoint: Endpoint)

    /**
     * Attempts to accept one outgoing Iroh packet without blocking.
     *
     * Returning false applies backpressure. Call [Endpoint.notifyWritable]
     * after capacity becomes available so Iroh retries the packet.
     */
    fun trySend(
        destination: TransportAddr.Custom,
        source: TransportAddr.Custom?,
        packet: ByteArray,
    ): Boolean

    /** Stops the transport and releases platform resources. */
    fun close()
}
