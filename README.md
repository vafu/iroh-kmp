# iroh-kmp

Kotlin Multiplatform bindings for [Iroh](https://iroh.computer), with a small
idiomatic Kotlin API and optional platform transports.

The repository currently contains four Gradle modules:

- `iroh-api`: platform-free Kotlin contracts and value types.
- `iroh-native`: the Android and Apple implementation backed directly by Rust Iroh.
- `iroh-bluetooth`: an optional Bluetooth implementation of Iroh custom transport.
- `sample:android`: a minimal application that creates an endpoint and displays its ID.

See [TODO.md](TODO.md) for intentionally deferred API and distribution work.

## Development

Run the host-side Kotlin and Rust tests with:

```sh
./gradlew :iroh-api:testAndroidHostTest :iroh-native:testAndroidHostTest
cargo test --workspace
```

Build the Android sample with:

```sh
./gradlew :sample:android:assembleDebug
```
