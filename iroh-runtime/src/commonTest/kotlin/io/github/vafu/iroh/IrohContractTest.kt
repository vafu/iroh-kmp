package io.github.vafu.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IrohContractTest {
    @Test
    fun alpnUsesItsUtf8ByteLength() {
        assertEquals("iroh-kmp/test/1", Endpoint.Alpn("iroh-kmp/test/1").value)
        assertFailsWith<IllegalArgumentException> { Endpoint.Alpn("") }
        assertFailsWith<IllegalArgumentException> { Endpoint.Alpn("é".repeat(128)) }
    }

    @Test
    fun customAddressesUseIrohBinaryEncoding() {
        val addresses = listOf(
            TransportAddr.Custom(0x2auL, byteArrayOf(0x00, 0x7f, 0xff.toByte())),
        )
        val encoded = addresses.toNativeLines()

        assertEquals("2a_007fff", encoded)
        assertEquals(addresses, encoded.toCustomAddrs())
    }

    @Test
    fun customRouteRoundTripsNativeToken() {
        val route = Route.Custom(0x5649_4c4c_4142_4c45uL)

        assertEquals(route, routeFromNative(route.toNativeToken()))
    }

    @Test
    fun duplicateTransportIdsAreRejectedBeforeNativeCreation() {
        assertFailsWith<IllegalArgumentException> {
            requireValidTransports(listOf(FakeTransport(7uL), FakeTransport(7uL)))
        }
    }
}

private class FakeTransport(override val id: ULong) : CustomTransport {
    override fun bind(endpoint: CustomTransport.Endpoint) = Unit

    override fun trySend(
        destination: TransportAddr.Custom,
        source: TransportAddr.Custom?,
        packet: ByteArray,
    ): Boolean = true

    override fun close() = Unit
}
