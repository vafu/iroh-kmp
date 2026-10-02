package io.github.vafu.iroh

/** Creates and binds a native Iroh [Endpoint] configured by [options]. */
suspend fun createEndpoint(options: EndpointOptions): Endpoint = EndpointImpl.create(
    native = nativeBindings(),
    alpns = options.alpns,
    relayMode = options.relayMode,
    customTransports = options.customTransports,
    addressLookups = options.addressLookups,
)
