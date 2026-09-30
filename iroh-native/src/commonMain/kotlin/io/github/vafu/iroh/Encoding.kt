package io.github.vafu.iroh

internal fun requireValidTransports(customTransports: List<CustomTransport>) {
    require(customTransports.map(CustomTransport::id).distinct().size == customTransports.size) {
        "Custom transport IDs must be unique"
    }
}

internal fun Route.toNativeToken(): String = when (this) {
    Route.Disconnected -> "disconnected"
    Route.Direct -> "direct"
    Route.Relay -> "relay"
    is Route.Custom -> "custom:$transportId"
}

internal fun routeFromNative(token: String): Route = when (token) {
    "disconnected" -> Route.Disconnected
    "direct" -> Route.Direct
    "relay" -> Route.Relay
    else -> Route.Custom(
        checkNotNull(token.removePrefix("custom:").takeIf { it != token }) {
            "Native Iroh returned an invalid route: $token"
        }.toULong(),
    )
}

internal val AddressLookup.Record.directAddresses: List<String>
    get() = addrs.mapNotNull { (it as? TransportAddr.Ip)?.address }

internal val AddressLookup.Record.relayUrls: List<String>
    get() = addrs.mapNotNull { (it as? TransportAddr.Relay)?.url }

internal val AddressLookup.Record.customAddresses: List<TransportAddr.Custom>
    get() = addrs.filterIsInstance<TransportAddr.Custom>()

internal fun addressLookupRecordFromNative(
    directAddresses: String,
    relayUrls: String,
    customAddresses: String,
    userData: String? = null,
): AddressLookup.Record = AddressLookup.Record(
    addrs = transportAddressesFromNative(directAddresses, relayUrls, customAddresses),
    userData = userData,
)

internal fun transportAddressesFromNative(
    directAddresses: String,
    relayUrls: String,
    customAddresses: String,
): List<TransportAddr> =
    directAddresses.lineValues().map(TransportAddr::Ip) +
        relayUrls.lineValues().map(TransportAddr::Relay) +
        customAddresses.toCustomAddrs()

internal fun List<TransportAddr.Custom>.toNativeLines(): String = joinToString("\n") { address ->
    address.id.toString(16) + "_" + address.data.toHex()
}

internal fun List<ByteArray>.toHexLines(): String = joinToString("\n", transform = ByteArray::toHex)

internal fun String.toCustomAddrs(): List<TransportAddr.Custom> =
    lineValues().map { encoded ->
        val separator = encoded.indexOf('_')
        require(separator > 0) { "Native Iroh returned an invalid custom address" }
        TransportAddr.Custom(
            id = encoded.substring(0, separator).toULong(16),
            data = encoded.substring(separator + 1).hexToBytes(),
        )
    }

internal fun String.lineValues(): List<String> = lines().filter(String::isNotEmpty)

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Native Iroh returned invalid hexadecimal bytes" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
