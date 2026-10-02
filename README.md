# iroh-kmp

Kotlin Multiplatform bindings for [Iroh](https://iroh.computer), with a small
idiomatic Kotlin API and optional platform transports.

The repository currently contains four Gradle modules:

- `iroh-api`: platform-free Kotlin contracts and value types.
- `iroh-native`: the Android and Apple implementation backed directly by Rust Iroh.
- `iroh-bluetooth`: an optional Bluetooth implementation of Iroh custom transport.
- `sample:android`: a minimal application that creates an endpoint and displays its ID.

See [TODO.md](TODO.md) for intentionally deferred API and distribution work.

## Kotlin API

Applications depend on `iroh-api` in shared code and inject an `EndpointFactory`.
Android and Apple applications use `NativeEndpointFactory` from `iroh-native`:

```kotlin
initializeAndroidIroh(applicationContext) // Android startup only

val endpoint = NativeEndpointFactory.bind(
    EndpointOptions(alpns = listOf(Endpoint.Alpn("com.example/game/1"))),
)
println(endpoint.id)
```

The optional `iroh-bluetooth` module implements Iroh's custom-transport and
address-lookup contracts using Kable. The application supplies its UUIDs,
transport identifier, adapter status, and authentication handshake; the library
owns scanning, GATT framing, reconnects, packet backpressure, and cleanup.

Native binaries are currently built from `native/iroh-kmp-native`. Until Maven
artifacts package them automatically, consuming source builds should use the
Android sample's `cargo ndk` task as the reference integration.

## Development

Run the host-side Kotlin and Rust tests with:

```sh
./gradlew :iroh-api:testAndroidHostTest :iroh-native:testAndroidHostTest \
  :iroh-bluetooth:testAndroidHostTest
cargo test --workspace
```

Build the Android sample with:

```sh
./gradlew :sample:android:assembleDebug
```
