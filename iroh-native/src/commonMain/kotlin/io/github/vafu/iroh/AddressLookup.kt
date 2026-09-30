package io.github.vafu.iroh

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalAtomicApi::class)
internal class AddressLookupBridge(
    val id: ULong,
    private val lookup: AddressLookup,
    private val native: NativeBindings,
    private val endpoint: Long,
) : NativeBindings.AddressLookupSink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = AtomicReference<Map<ULong, Job>>(emptyMap())

    override fun publish(
        directAddresses: String,
        relayUrls: String,
        customAddresses: String,
        userData: String?,
    ) {
        scope.launch {
            lookup.publish(
                addressLookupRecordFromNative(
                    directAddresses,
                    relayUrls,
                    customAddresses,
                    userData,
                ),
            )
        }
    }

    override fun startResolve(requestId: Long, endpointId: String): Boolean {
        val results = lookup.resolve(Endpoint.Id(endpointId)) ?: return false
        val request = requestId.toULong()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                results.collect { emit(request, it) }
                complete(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                runCatching {
                    fail(request, error.message ?: error::class.simpleName ?: "Address lookup failed")
                }
            } finally {
                remove(request)
            }
        }
        check(add(request, job)) { "Duplicate native address lookup request: $request" }
        job.start()
        return true
    }

    override fun cancelResolve(requestId: Long) {
        remove(requestId.toULong())?.cancel()
    }

    fun close() {
        scope.cancel()
    }

    private fun emit(requestId: ULong, record: AddressLookup.Record) {
        native.emitAddressLookupResult(
            endpoint,
            id.toLong(),
            requestId.toLong(),
            record.directAddresses.joinToString("\n"),
            record.relayUrls.joinToString("\n"),
            record.customAddresses.toNativeLines(),
            record.userData,
        )
    }

    private fun complete(requestId: ULong) {
        native.completeAddressLookup(endpoint, id.toLong(), requestId.toLong())
    }

    private fun fail(requestId: ULong, message: String) {
        native.failAddressLookup(
            endpoint,
            id.toLong(),
            requestId.toLong(),
            message,
        )
    }

    private fun add(requestId: ULong, job: Job): Boolean {
        while (true) {
            val current = requests.load()
            if (requestId in current) return false
            if (requests.compareAndSet(current, current + (requestId to job))) return true
        }
    }

    private fun remove(requestId: ULong): Job? {
        while (true) {
            val current = requests.load()
            val job = current[requestId] ?: return null
            if (requests.compareAndSet(current, current - requestId)) return job
        }
    }
}
