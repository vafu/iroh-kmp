# Platform address lookup

This adapter makes Kotlin-provided discovery services available through Iroh's
native `AddressLookup` trait.

Each Iroh resolution receives a native request ID and an unbounded result
channel. Starting the request invokes the platform callback immediately, but
the callback only creates a Kotlin coroutine and therefore never blocks Iroh's
runtime. Kotlin flow emissions enter the request channel without polling or
lossy buffering.

Dropping Iroh's result stream removes the request and tells Kotlin to cancel
its collection job. Completion, failure, and cancellation race through the
same request map, so only the first terminal operation takes effect.
