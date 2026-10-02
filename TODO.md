# TODO

This file intentionally collects work that is useful but not required for the
first reusable Android release.

## Iroh API coverage

- Persist or inject an endpoint `SecretKey`.
- Support unidirectional streams.
- Expose raw incoming/accepting/connecting states and protocol handlers.
- Expose endpoint online state, remote address inspection, and runtime relay updates.
- Expose connection statistics, RTT, side, stable ID, and complete path events.
- Add partial stream reads, reset/stop, priority, and byte counters.
- Model Iroh errors as stable Kotlin error categories instead of message-only failures.
- Evaluate built-in Iroh address-lookup presets in addition to application lookups.
- Evaluate a Kotlin API for transport and QUIC configuration.

## Platforms and distribution

- Run Apple native tests and the iOS sample on macOS hardware.
- Add Wear OS sample coverage.
- Publish signed Maven Central artifacts and Gradle module metadata.
- Choose and add the public repository license before the first public release.
- Add release automation and an Iroh-version compatibility table.
- Add automated upstream Iroh API drift reporting from rustdoc JSON.

## Bluetooth

- Validate the CoreBluetooth path on physical iPhone hardware.
- Decide which reconnect metadata belongs in this library versus applications.
- Add BLE throughput, latency, backpressure, and reconnect instrumentation.
- Test transport behavior across adapter power and permission transitions.
- Add a public diagnostics sink once its event vocabulary is clear from measurements.
- Decide whether BLE peripheral/server support belongs here or in a sibling module.

## API ergonomics

- Replace `BluetoothTransportOptions` byte-array value semantics if it becomes a
  frequently compared or persisted model.
- Decide whether endpoint identity parsing/validation should happen in `Endpoint.Id`
  or remain delegated to native Iroh.
- Add structured cancellation/closure causes after the first external consumer needs them.
