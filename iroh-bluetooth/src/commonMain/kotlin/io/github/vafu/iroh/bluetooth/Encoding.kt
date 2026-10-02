package io.github.vafu.iroh.bluetooth

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Iroh endpoint ID has invalid hex length" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
