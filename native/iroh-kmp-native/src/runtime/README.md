# Native Iroh runtime

`NativeEndpoint` owns one Tokio runtime and one optionally bound Iroh endpoint.
Public module contracts live in `mod.rs`; endpoint construction and connection
operations live in sibling implementation files. Before binding, the platform
may configure ALPNs and relays and register custom transports and address
lookups. After binding, connection and stream resources are represented by
opaque numeric handles at the native boundary.

Datagrams and reliable streams delegate directly to Iroh. Selected-route
updates come from Iroh's path stream. JNI and the C ABI expose a blocking
`await route change` operation so Kotlin can publish reactive state without
polling.

The platform registers zero or more packet sinks before the endpoint is bound.
Each registration has a unique transport ID, mutable local addresses, its own
sender waker, and a bounded inbound Tokio channel. Iroh offers complete packets
directly to the matching sink. Saturation returns `Pending`; a capacity
notification wakes that sender.

The platform may also register address lookups before binding. Each lookup is
installed directly on Iroh's endpoint builder. Resolution results are streamed
through per-request channels; terminal operations remove the request exactly
once and dropped streams cancel their platform coroutine.
