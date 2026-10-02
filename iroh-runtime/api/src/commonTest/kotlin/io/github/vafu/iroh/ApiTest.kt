package io.github.vafu.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ApiTest {
    @Test
    fun alpnValidatesItsUtf8ByteLength() {
        assertEquals("example/1", Endpoint.Alpn("example/1").value)
        assertFailsWith<IllegalArgumentException> { Endpoint.Alpn("") }
        assertFailsWith<IllegalArgumentException> { Endpoint.Alpn("é".repeat(128)) }
    }

    @Test
    fun customAddressesCompareTheirContents() {
        val first = TransportAddr.Custom(7uL, byteArrayOf(1, 2, 3))
        val equal = TransportAddr.Custom(7uL, byteArrayOf(1, 2, 3))
        val different = TransportAddr.Custom(8uL, byteArrayOf(1, 2, 3))

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, different)
    }

    @Test
    fun endpointAddressNeedsOnlyAnIdentity() {
        val id = Endpoint.Id("endpoint")

        assertEquals(Endpoint.Addr(id), Endpoint.Addr(id, emptyList()))
    }
}
