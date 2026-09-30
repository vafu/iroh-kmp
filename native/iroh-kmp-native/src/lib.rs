//! Native Iroh implementation behind the Kotlin Multiplatform API.

#[allow(unsafe_code)]
mod ffi;
mod registry;
mod runtime;
mod transport;

#[cfg(any(target_os = "android", test))]
#[allow(unsafe_code)]
mod jni;

pub use runtime::{IrohError, NativeEndpoint, Route, StreamPair};
