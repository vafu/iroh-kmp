package io.github.vafu.iroh.bluetooth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class BinaryFramingTest {
    @Test
    fun decoderAcceptsFragmentedAndCoalescedFrames() {
        val decoder = LengthPrefixedMessageDecoder(16, "test")
        val first = byteArrayOf(1, 2, 3).withLengthPrefix(16, "test")
        val second = byteArrayOf(4).withLengthPrefix(16, "test")

        assertEquals(emptyList(), decoder.append(first.copyOfRange(0, 5)))
        val messages = decoder.append(first.copyOfRange(5, first.size) + second)

        assertEquals(2, messages.size)
        assertContentEquals(byteArrayOf(1, 2, 3), messages[0])
        assertContentEquals(byteArrayOf(4), messages[1])
    }

    @Test
    fun oversizedFrameIsRejectedBeforePayloadArrives() {
        val decoder = LengthPrefixedMessageDecoder(2, "test")

        assertFailsWith<IllegalArgumentException> {
            decoder.append(byteArrayOf(0, 0, 0, 3))
        }
    }

    @Test
    fun endpointIdsDecodeFromHex() {
        assertContentEquals(byteArrayOf(0x01, 0xab.toByte()), "01ab".hexToBytes())
    }
}
