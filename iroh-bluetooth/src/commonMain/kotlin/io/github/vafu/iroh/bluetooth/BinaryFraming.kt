package io.github.vafu.iroh.bluetooth

internal fun ByteArray.withLengthPrefix(maximumSize: Int, label: String): ByteArray {
    require(size <= maximumSize) { "$label exceeds the $maximumSize-byte limit" }
    return size.toBigEndianBytes() + this
}

internal fun Int.toBigEndianBytes(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(),
    (this ushr 16).toByte(),
    (this ushr 8).toByte(),
    toByte(),
)

internal fun ByteArray.toBigEndianInt(): Int {
    require(size >= Int.SIZE_BYTES) { "A 32-bit length prefix requires four bytes" }
    return ((this[0].toInt() and 0xff) shl 24) or
        ((this[1].toInt() and 0xff) shl 16) or
        ((this[2].toInt() and 0xff) shl 8) or
        (this[3].toInt() and 0xff)
}

internal class LengthPrefixedMessageReader(
    private val maximumSize: Int,
    private val label: String,
    private val receiveChunk: suspend () -> ByteArray,
) {
    private var buffered = ByteArray(0)

    suspend fun receive(): ByteArray {
        val length = read(Int.SIZE_BYTES).toBigEndianInt()
        require(length in 0..maximumSize) { "$label exceeds the $maximumSize-byte limit" }
        return read(length)
    }

    private suspend fun read(count: Int): ByteArray {
        if (count == 0) return ByteArray(0)
        while (buffered.size < count) {
            val chunk = receiveChunk()
            require(chunk.isNotEmpty()) { "$label stream returned an empty chunk" }
            buffered += chunk
        }
        return buffered.copyOfRange(0, count).also {
            buffered = buffered.copyOfRange(count, buffered.size)
        }
    }
}

internal class LengthPrefixedMessageDecoder(
    private val maximumSize: Int,
    private val label: String,
) {
    private var buffered = ByteArray(0)

    fun append(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        buffered += chunk
        val messages = mutableListOf<ByteArray>()
        while (buffered.size >= Int.SIZE_BYTES) {
            val length = buffered.toBigEndianInt()
            require(length in 0..maximumSize) { "$label exceeds the $maximumSize-byte limit" }
            val frameSize = Int.SIZE_BYTES + length
            if (buffered.size < frameSize) break
            messages += buffered.copyOfRange(Int.SIZE_BYTES, frameSize)
            buffered = buffered.copyOfRange(frameSize, buffered.size)
        }
        return messages
    }
}
