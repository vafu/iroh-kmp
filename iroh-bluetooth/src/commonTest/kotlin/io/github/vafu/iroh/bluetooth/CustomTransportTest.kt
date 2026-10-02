package io.github.vafu.iroh.bluetooth

import io.github.vafu.iroh.CustomTransport
import io.github.vafu.iroh.Endpoint
import io.github.vafu.iroh.TransportAddr
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.MutableStateFlow

internal class CustomTransportTest {
    @Test
    fun bindAdvertisesLocalIdentityAndSendStartsConnection() {
        var startedWith: String? = null
        val transport = BluetoothCustomTransport(
            id = 9uL,
            remoteAddress = byteArrayOf(4, 5),
            connections = MutableStateFlow(null),
            connect = { startedWith = it },
        )
        val endpoint = RecordingEndpoint("01ab")

        transport.bind(endpoint)
        val accepted = transport.trySend(
            destination = TransportAddr.Custom(9uL, byteArrayOf(4, 5)),
            source = null,
            packet = byteArrayOf(7),
        )

        assertFalse(accepted)
        assertEquals("01ab", startedWith)
        assertEquals(9uL, endpoint.addresses.single().id)
        assertContentEquals(byteArrayOf(1, 0xab.toByte()), endpoint.addresses.single().data)
        transport.close()
    }

    @Test
    fun sendRejectsAnotherTransportAddressWithoutConnecting() {
        var starts = 0
        val transport = BluetoothCustomTransport(
            id = 9uL,
            remoteAddress = byteArrayOf(4, 5),
            connections = MutableStateFlow(null),
            connect = { starts += 1 },
        )
        transport.bind(RecordingEndpoint("01ab"))

        assertFalse(
            transport.trySend(
                destination = TransportAddr.Custom(9uL, byteArrayOf(0)),
                source = null,
                packet = byteArrayOf(7),
            ),
        )
        assertEquals(0, starts)
        transport.close()
    }
}

private class RecordingEndpoint(id: String) : CustomTransport.Endpoint {
    override val endpointId = Endpoint.Id(id)
    var addresses = emptyList<TransportAddr.Custom>()

    override fun setLocalAddresses(addresses: List<TransportAddr.Custom>) {
        this.addresses = addresses
    }

    override fun offer(
        source: TransportAddr.Custom,
        destination: TransportAddr.Custom?,
        packet: ByteArray,
    ): Boolean = true

    override fun notifyWritable() = Unit
}
