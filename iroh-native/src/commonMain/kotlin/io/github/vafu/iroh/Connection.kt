package io.github.vafu.iroh

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalAtomicApi::class)
internal class ConnectionImpl(
    private val native: NativeBindings,
    private val endpoint: Long,
    private val handle: Long,
) : Connection {
    private val isClosed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableRoutes = MutableStateFlow<Route>(Route.Disconnected)

    override val remoteId = Endpoint.Id(native.connectionRemoteId(endpoint, handle))
    override val routes: Flow<Route> = mutableRoutes.asStateFlow()

    init {
        scope.launch {
            try {
                var route: Route = Route.Disconnected
                while (true) {
                    route = routeFromNative(
                        native.awaitRouteChange(endpoint, handle, route.toNativeToken()),
                    )
                    mutableRoutes.value = route
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableRoutes.value = Route.Disconnected
            }
        }
    }

    override suspend fun openBi(): BiStream = nativeCall {
        native.openBi(endpoint, handle).toBiStream(native, endpoint)
    }

    override suspend fun acceptBi(): BiStream = nativeCall {
        native.acceptBi(endpoint, handle).toBiStream(native, endpoint)
    }

    override fun sendDatagram(data: ByteArray) {
        native.sendDatagram(endpoint, handle, data)
    }

    override suspend fun sendDatagramWait(data: ByteArray) = nativeCall {
        native.sendDatagramWait(endpoint, handle, data)
    }

    override suspend fun readDatagram(): ByteArray = nativeCall {
        native.readDatagram(endpoint, handle)
    }

    override suspend fun closed(): String = nativeCall {
        native.connectionClosed(endpoint, handle)
    }

    override fun close(errorCode: ULong, reason: ByteArray) {
        if (!isClosed.compareAndSet(false, true)) return
        scope.cancel()
        native.closeConnection(endpoint, handle, errorCode.toLong(), reason)
    }
}

private class SendStreamImpl(
    private val native: NativeBindings,
    private val endpoint: Long,
    private val handle: Long,
) : SendStream {
    override suspend fun writeAll(data: ByteArray) = nativeCall {
        native.writeAll(endpoint, handle, data)
    }

    override fun finish() {
        native.finishSend(endpoint, handle)
    }
}

private class ReceiveStreamImpl(
    private val native: NativeBindings,
    private val endpoint: Long,
    private val handle: Long,
) : ReceiveStream {
    override suspend fun readExact(length: Int): ByteArray = nativeCall {
        require(length >= 0) { "Read length must not be negative" }
        native.readExact(endpoint, handle, length)
    }

    override suspend fun readToEnd(sizeLimit: Int): ByteArray = nativeCall {
        require(sizeLimit >= 0) { "Size limit must not be negative" }
        native.readToEnd(endpoint, handle, sizeLimit)
    }
}

private fun LongArray.toBiStream(native: NativeBindings, endpoint: Long): BiStream {
    check(size == 2) { "Native Iroh returned an invalid stream pair" }
    return BiStream(
        send = SendStreamImpl(native, endpoint, this[0]),
        receive = ReceiveStreamImpl(native, endpoint, this[1]),
    )
}
