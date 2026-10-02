package io.github.vafu.iroh.bluetooth

import io.github.vafu.iroh.AddressLookup
import io.github.vafu.iroh.CustomTransport
import io.github.vafu.iroh.Endpoint
import io.github.vafu.iroh.TransportAddr
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow

/** Platform Bluetooth adapter availability relevant to an Iroh transport. */
enum class BluetoothStatus {
    /** The adapter is powered and application Bluetooth operations may proceed. */
    Available,

    /** Bluetooth operations remain suspended until availability changes. */
    Unavailable,
}

/** Supplies the current Bluetooth adapter status followed by every transition. */
fun interface BluetoothStatusProvider {
    /** Returns a replayable stream whose first value is the current status. */
    fun updates(): Flow<BluetoothStatus>
}

/** Authenticated control link established before Iroh packets may flow. */
interface BluetoothLink {
    /** Platform identifier suitable for reconnecting to this peripheral. */
    val peripheralId: String

    /** Sends one framed application control message. */
    suspend fun sendControl(message: ByteArray)

    /** Receives one complete framed application control message. */
    suspend fun receiveControl(): ByteArray

    /** Waits until all previously queued control messages have reached the BLE stack. */
    suspend fun flushControl()
}

/** Authenticates a discovered BLE peer and returns its fresh Iroh addresses. */
fun interface BluetoothLinkAuthenticator {
    /** Performs application-owned authentication over [link]. */
    suspend fun authenticate(link: BluetoothLink, localEndpointId: Endpoint.Id): Endpoint.Addr
}

/** UUIDs and reconnect policy for an Iroh BLE packet transport. */
data class BluetoothTransportOptions(
    /** Iroh identity expected at the remote end of this link. */
    val remoteEndpointId: Endpoint.Id,
    /** Opaque address used by this custom transport for the remote endpoint. */
    val remoteAddress: ByteArray,
    /** Stable custom-transport identifier shared with the peer implementation. */
    val transportId: ULong,
    /** Advertised GATT service containing the control and packet characteristics. */
    val serviceUuid: String,
    /** Reliable, acknowledged control characteristic UUID. */
    val controlCharacteristicUuid: String,
    /** Unacknowledged Iroh packet characteristic UUID. */
    val packetCharacteristicUuid: String,
    /** Application authentication performed after GATT connects. */
    val authenticator: BluetoothLinkAuthenticator,
    /** Adapter state source supplied by the host application. */
    val statusProvider: BluetoothStatusProvider,
    /** Previously retained platform peripheral identifier, when available. */
    val peripheralId: String? = null,
    /** Called when scanning discovers a different reconnect identifier. */
    val onPeripheralId: suspend (String) -> Unit = {},
    /** Maximum time spent scanning before retrying. */
    val scanTimeout: Duration = 20.seconds,
    /** Maximum time spent establishing one GATT connection. */
    val connectTimeout: Duration = 15.seconds,
    /** Maximum time spent writing one GATT fragment. */
    val writeTimeout: Duration = 10.seconds,
) {
    init {
        require(remoteAddress.isNotEmpty()) { "Bluetooth transport address cannot be empty" }
        require(scanTimeout.isPositive()) { "Bluetooth scan timeout must be positive" }
        require(connectTimeout.isPositive()) { "Bluetooth connect timeout must be positive" }
        require(writeTimeout.isPositive()) { "Bluetooth write timeout must be positive" }
    }
}

/** A reconnecting BLE link usable as both an Iroh custom transport and address lookup. */
interface BluetoothTransport : CustomTransport, AddressLookup {
    /** Most recently connected platform peripheral identifier. */
    val peripheralId: String?

    /** Suspends reconnect attempts and closes the current physical link. */
    fun pause()

    /** Allows reconnect attempts after [pause]. */
    fun resume()

    /** Closes the transport and waits for BLE cleanup. */
    suspend fun closeAndWait()
}

/** Creates a dormant BLE transport. It starts connecting when Iroh first sends a packet. */
fun createBluetoothTransport(options: BluetoothTransportOptions): BluetoothTransport =
    DefaultBluetoothTransport(options)

/** Adds the custom address required to reach this endpoint through [transport]. */
fun Endpoint.Addr.withBluetoothAddress(
    transport: BluetoothTransportOptions,
): Endpoint.Addr = withCustomAddress(transport.transportId, transport.remoteAddress)

/** Adds or replaces the custom address identified by [transportId]. */
fun Endpoint.Addr.withCustomAddress(
    transportId: ULong,
    address: ByteArray,
): Endpoint.Addr = copy(
    addrs = addrs.filterNot { it is TransportAddr.Custom && it.id == transportId } +
        TransportAddr.Custom(transportId, address.copyOf()),
)
