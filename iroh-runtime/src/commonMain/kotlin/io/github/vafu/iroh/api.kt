package io.github.vafu.iroh

/** Native Rust-backed implementation of [EndpointFactory]. */
object NativeEndpointFactory : EndpointFactory {
    override suspend fun bind(options: EndpointOptions): Endpoint = EndpointImpl.create(
        native = nativeBindings(),
        alpns = options.alpns,
        relayMode = options.relayMode,
        customTransports = options.customTransports,
        addressLookups = options.addressLookups,
    )
}

/**
 * Creates and binds a native Iroh [Endpoint].
 *
 * This convenience overload preserves the compact call shape used by existing
 * applications. Code that supplies implementations through dependency injection
 * can use [NativeEndpointFactory] directly.
 */
suspend fun createEndpoint(
    alpns: List<Endpoint.Alpn>,
    relayMode: RelayMode = RelayMode.Disabled,
    customTransports: List<CustomTransport> = emptyList(),
    addressLookups: List<AddressLookup> = emptyList(),
): Endpoint = NativeEndpointFactory.bind(
    EndpointOptions(
        alpns = alpns,
        relayMode = relayMode,
        customTransports = customTransports,
        addressLookups = addressLookups,
    ),
)
