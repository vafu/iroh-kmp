package io.github.vafu.iroh.bluetooth

import io.github.vafu.iroh.Endpoint
import io.github.vafu.iroh.TransportAddr
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf

internal class BluetoothApiTest {
    @Test
    fun customAddressIsAddedAndDefensivelyCopied() {
        val source = byteArrayOf(1, 2, 3)
        val options = options(source)
        val address = Endpoint.Addr(Endpoint.Id("peer")).withBluetoothAddress(options)
        source[0] = 9

        val custom = address.addrs.single() as TransportAddr.Custom
        assertEquals(7uL, custom.id)
        assertContentEquals(byteArrayOf(1, 2, 3), custom.data)
    }

    @Test
    fun matchingCustomAddressIsReplaced() {
        val original = Endpoint.Addr(
            Endpoint.Id("peer"),
            listOf(TransportAddr.Custom(7uL, byteArrayOf(0))),
        )

        val updated = original.withCustomAddress(7uL, byteArrayOf(1))

        assertEquals(1, updated.addrs.size)
        assertContentEquals(byteArrayOf(1), (updated.addrs.single() as TransportAddr.Custom).data)
    }

    @Test
    fun emptyTransportAddressIsRejected() {
        assertFailsWith<IllegalArgumentException> { options(byteArrayOf()) }
    }

    private fun options(address: ByteArray) = BluetoothTransportOptions(
        remoteEndpointId = Endpoint.Id("peer"),
        remoteAddress = address,
        transportId = 7uL,
        serviceUuid = "00000000-0000-0000-0000-000000000001",
        controlCharacteristicUuid = "00000000-0000-0000-0000-000000000002",
        packetCharacteristicUuid = "00000000-0000-0000-0000-000000000003",
        authenticator = BluetoothLinkAuthenticator { _, _ -> Endpoint.Addr(Endpoint.Id("peer")) },
        statusProvider = BluetoothStatusProvider { flowOf(BluetoothStatus.Available) },
    )
}
