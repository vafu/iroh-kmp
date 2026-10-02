//! JNI facade used by the Android KMP actual implementation.

use std::fmt;
use std::sync::Arc;
#[cfg(target_os = "android")]
use std::{ffi::c_void, sync::OnceLock};

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JLongArray, JObject, JString};
use jni::refs::Global;
use jni::sys::{jint, jlong};
use jni::{JValue, JavaVM, jni_sig, jni_str};

use iroh::address_lookup::EndpointData;
use iroh_base::CustomAddr;

use crate::registry;
use crate::runtime::{AddressLookupSink, EncodedEndpointData, PacketSink};

#[cfg(target_os = "android")]
static ANDROID_CONTEXT: OnceLock<Global<JObject<'static>>> = OnceLock::new();

type BridgeResult<T> = Result<T, BridgeError>;

#[derive(Debug)]
enum BridgeError {
    Jni(jni::errors::Error),
    Iroh(crate::IrohError),
}

impl fmt::Display for BridgeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Jni(error) => error.fmt(formatter),
            Self::Iroh(error) => error.fmt(formatter),
        }
    }
}

impl std::error::Error for BridgeError {}

impl From<jni::errors::Error> for BridgeError {
    fn from(error: jni::errors::Error) -> Self {
        Self::Jni(error)
    }
}

impl From<crate::IrohError> for BridgeError {
    fn from(error: crate::IrohError) -> Self {
        Self::Iroh(error)
    }
}

#[derive(Debug)]
struct JniPacketSink {
    sink: Global<JObject<'static>>,
}

impl PacketSink for JniPacketSink {
    fn offer(
        &self,
        destination: &CustomAddr,
        source: Option<&CustomAddr>,
        bytes: &[u8],
    ) -> Result<bool, crate::IrohError> {
        JavaVM::singleton()
            .and_then(|vm| {
                vm.attach_current_thread(|env| {
                    let destination = java_bytes(env, destination.data()).map_err(jni_error)?;
                    let source = source
                        .map(|address| java_bytes(env, address.data()))
                        .transpose()
                        .map_err(jni_error)?;
                    let packet = java_bytes(env, bytes).map_err(jni_error)?;
                    let null = JObject::null();
                    env.call_method(
                        &self.sink,
                        jni_str!("offer"),
                        jni_sig!("([B[B[B)Z"),
                        &[
                            JValue::Object(destination.as_ref()),
                            JValue::Object(source.as_ref().map_or(&null, JByteArray::as_ref)),
                            JValue::Object(packet.as_ref()),
                        ],
                    )?
                    .z()
                })
            })
            .map_err(|error| crate::IrohError::new(format!("JNI packet sink failed: {error}")))
    }
}

#[derive(Debug)]
struct JniAddressLookupSink {
    sink: Global<JObject<'static>>,
}

impl AddressLookupSink for JniAddressLookupSink {
    fn publish(&self, data: &EndpointData) -> Result<(), crate::IrohError> {
        let encoded = EncodedEndpointData::from(data);
        JavaVM::singleton()
            .and_then(|vm| {
                vm.attach_current_thread(|env| {
                    let direct = JString::from_str(env, encoded.direct_addresses)?;
                    let relays = JString::from_str(env, encoded.relay_urls)?;
                    let custom = JString::from_str(env, encoded.custom_addresses)?;
                    let user_data = encoded
                        .user_data
                        .map(|value| JString::from_str(env, value))
                        .transpose()?;
                    let null = JObject::null();
                    env.call_method(
                        &self.sink,
                        jni_str!("publish"),
                        jni_sig!("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
                        &[
                            JValue::Object(direct.as_ref()),
                            JValue::Object(relays.as_ref()),
                            JValue::Object(custom.as_ref()),
                            JValue::Object(user_data.as_ref().map_or(&null, JString::as_ref)),
                        ],
                    )?;
                    Ok(())
                })
            })
            .map_err(|error| crate::IrohError::new(format!("JNI address publish failed: {error}")))
    }

    fn start_resolve(
        &self,
        request_id: u64,
        endpoint_id: iroh_base::EndpointId,
    ) -> Result<bool, crate::IrohError> {
        JavaVM::singleton()
            .and_then(|vm| {
                vm.attach_current_thread(|env| {
                    let endpoint_id = JString::from_str(env, endpoint_id.to_string())?;
                    env.call_method(
                        &self.sink,
                        jni_str!("startResolve"),
                        jni_sig!("(JLjava/lang/String;)Z"),
                        &[
                            JValue::Long(request_id.cast_signed()),
                            JValue::Object(endpoint_id.as_ref()),
                        ],
                    )?
                    .z()
                })
            })
            .map_err(|error| crate::IrohError::new(format!("JNI address resolve failed: {error}")))
    }

    fn cancel_resolve(&self, request_id: u64) -> Result<(), crate::IrohError> {
        JavaVM::singleton()
            .and_then(|vm| {
                vm.attach_current_thread(|env| {
                    env.call_method(
                        &self.sink,
                        jni_str!("cancelResolve"),
                        jni_sig!("(J)V"),
                        &[JValue::Long(request_id.cast_signed())],
                    )?;
                    Ok(())
                })
            })
            .map_err(|error| crate::IrohError::new(format!("JNI address cancel failed: {error}")))
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_installAndroidContext<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    context: JObject<'local>,
) {
    unowned
        .with_env(|_env| -> BridgeResult<()> {
            #[cfg(target_os = "android")]
            if ANDROID_CONTEXT.get().is_none() {
                let vm = _env.get_java_vm()?;
                let context = _env.new_global_ref(context)?;
                let context_pointer = context.as_raw().cast::<c_void>();
                if ANDROID_CONTEXT.set(context).is_ok() {
                    unsafe {
                        iroh::dns::install_android_jni_context(
                            vm.get_raw().cast::<c_void>(),
                            context_pointer,
                        );
                    }
                }
            }
            #[cfg(not(target_os = "android"))]
            let _ = context;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_create<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
) -> jlong {
    unowned
        .with_env(|_| -> BridgeResult<jlong> { Ok(registry::create()?.cast_signed()) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_networkChange<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
) {
    unowned
        .with_env(|_| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?.network_change()?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_addAlpn<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    alpn: JByteArray<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?.add_alpn(rust_bytes(env, &alpn)?)?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_setRelayUrls<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    urls: JString<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?.set_relay_urls(string_lines(env, &urls)?)?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_registerCustomTransport<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    transport_id: jlong,
    packet_sink: JObject<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?.register_custom_transport(
                transport_id.cast_unsigned(),
                Arc::new(JniPacketSink {
                    sink: env.new_global_ref(packet_sink)?,
                }),
            )?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_registerAddressLookup<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    lookup_id: jlong,
    sink: JObject<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?.register_address_lookup(
                lookup_id.cast_unsigned(),
                Arc::new(JniAddressLookupSink {
                    sink: env.new_global_ref(sink)?,
                }),
            )?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_emitAddressLookupResult<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    lookup_id: jlong,
    request_id: jlong,
    direct_addresses: JString<'local>,
    relay_urls: JString<'local>,
    custom_addresses: JString<'local>,
    user_data: JString<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            let user_data = if user_data.is_null() {
                None
            } else {
                Some(user_data.try_to_string(env)?)
            };
            registry::get(handle.cast_unsigned())?.emit_address_lookup_result(
                lookup_id.cast_unsigned(),
                request_id.cast_unsigned(),
                &string_lines(env, &direct_addresses)?,
                &string_lines(env, &relay_urls)?,
                &string_lines(env, &custom_addresses)?,
                user_data.as_deref(),
            )?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

macro_rules! lookup_terminal {
    ($name:ident, $method:ident) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name<'local>(
            mut unowned: EnvUnowned<'local>,
            _this: JObject<'local>,
            handle: jlong,
            lookup_id: jlong,
            request_id: jlong,
        ) {
            unowned
                .with_env(|_| -> BridgeResult<()> {
                    registry::get(handle.cast_unsigned())?
                        .$method(lookup_id.cast_unsigned(), request_id.cast_unsigned())?;
                    Ok(())
                })
                .resolve::<ThrowRuntimeExAndDefault>();
        }
    };
}

lookup_terminal!(
    Java_io_github_vafu_iroh_AndroidNativeBindings_completeAddressLookup,
    complete_address_lookup
);

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_failAddressLookup<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    lookup_id: jlong,
    request_id: jlong,
    message: JString<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?.fail_address_lookup(
                lookup_id.cast_unsigned(),
                request_id.cast_unsigned(),
                message.try_to_string(env)?,
            )?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_setCustomTransportAddresses<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    transport_id: jlong,
    addresses: JString<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            let addresses = addresses
                .try_to_string(env)?
                .lines()
                .filter(|value| !value.is_empty())
                .map(decode_hex)
                .collect::<Result<Vec<_>, _>>()?;
            registry::get(handle.cast_unsigned())?
                .set_local_addresses(transport_id.cast_unsigned(), &addresses)?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_bind<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
) {
    unowned
        .with_env(|_| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?.bind()?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_destroy<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
) {
    unowned
        .with_env(|_| -> BridgeResult<()> {
            registry::destroy(handle.cast_unsigned())?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_endpointId<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> JString<'local> {
    unowned
        .with_env(|env| -> BridgeResult<JString<'local>> {
            Ok(JString::from_str(
                env,
                registry::get(handle.cast_unsigned())?.endpoint_id(),
            )?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_endpointData<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> JString<'local> {
    unowned
        .with_env(|env| -> BridgeResult<JString<'local>> {
            let data = EndpointData::from(registry::get(handle.cast_unsigned())?.endpoint_addr()?);
            let encoded = EncodedEndpointData::from(&data);
            Ok(JString::from_str(
                env,
                format!(
                    "{}\u{1e}{}\u{1e}{}",
                    encoded.direct_addresses, encoded.relay_urls, encoded.custom_addresses,
                ),
            )?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_connect<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    endpoint_id: JString<'local>,
    direct_addresses: JString<'local>,
    relay_urls: JString<'local>,
    custom_addresses: JString<'local>,
    alpn: JByteArray<'local>,
) -> jlong {
    unowned
        .with_env(|env| -> BridgeResult<jlong> {
            Ok(registry::get(handle.cast_unsigned())?
                .connect(
                    &endpoint_id.try_to_string(env)?,
                    &string_lines(env, &direct_addresses)?,
                    &string_lines(env, &relay_urls)?,
                    &string_lines(env, &custom_addresses)?,
                    &rust_bytes(env, &alpn)?,
                )?
                .cast_signed())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_connectionRemoteId<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    connection: jlong,
) -> JString<'local> {
    unowned
        .with_env(|env| -> BridgeResult<JString<'local>> {
            Ok(JString::from_str(
                env,
                registry::get(handle.cast_unsigned())?
                    .connection_remote_id(connection.cast_unsigned())?,
            )?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

macro_rules! stream_pair {
    ($name:ident, $method:ident) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name<'local>(
            mut unowned: EnvUnowned<'local>,
            _this: JObject<'local>,
            handle: jlong,
            connection: jlong,
        ) -> JLongArray<'local> {
            unowned
                .with_env(|env| -> BridgeResult<JLongArray<'local>> {
                    let pair = registry::get(handle.cast_unsigned())?
                        .$method(connection.cast_unsigned())?;
                    let result = JLongArray::new(env, 2)?;
                    result.set_region(
                        env,
                        0,
                        &[pair.send.cast_signed(), pair.receive.cast_signed()],
                    )?;
                    Ok(result)
                })
                .resolve::<ThrowRuntimeExAndDefault>()
        }
    };
}

stream_pair!(
    Java_io_github_vafu_iroh_AndroidNativeBindings_openBi,
    open_bi
);
stream_pair!(
    Java_io_github_vafu_iroh_AndroidNativeBindings_acceptBi,
    accept_bi
);

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_notifyCustomTransportCapacity<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    transport_id: jlong,
) {
    unowned
        .with_env(|_| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?
                .notify_transport_capacity(transport_id.cast_unsigned())?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_submitCustomPacket<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    transport_id: jlong,
    source: JByteArray<'local>,
    destination: JByteArray<'local>,
    bytes: JByteArray<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            let destination = if destination.is_null() {
                None
            } else {
                Some(rust_bytes(env, &destination)?)
            };
            registry::get(handle.cast_unsigned())?.submit_custom_packet(
                transport_id.cast_unsigned(),
                &rust_bytes(env, &source)?,
                destination.as_deref(),
                rust_bytes(env, &bytes)?,
            )?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

macro_rules! bytes_input {
    ($name:ident, $method:ident) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name<'local>(
            mut unowned: EnvUnowned<'local>,
            _this: JObject<'local>,
            handle: jlong,
            resource: jlong,
            bytes: JByteArray<'local>,
        ) {
            unowned
                .with_env(|env| -> BridgeResult<()> {
                    registry::get(handle.cast_unsigned())?
                        .$method(resource.cast_unsigned(), rust_bytes(env, &bytes)?)?;
                    Ok(())
                })
                .resolve::<ThrowRuntimeExAndDefault>();
        }
    };
}

bytes_input!(
    Java_io_github_vafu_iroh_AndroidNativeBindings_sendDatagram,
    send_datagram
);
bytes_input!(
    Java_io_github_vafu_iroh_AndroidNativeBindings_sendDatagramWait,
    send_datagram_wait
);

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_readDatagram<'local>(
    unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    connection: jlong,
) -> JByteArray<'local> {
    receive_bytes(unowned, handle, |endpoint| {
        endpoint.read_datagram(connection.cast_unsigned())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_connectionClosed<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    connection: jlong,
) -> JString<'local> {
    unowned
        .with_env(|env| -> BridgeResult<JString<'local>> {
            Ok(JString::from_str(
                env,
                registry::get(handle.cast_unsigned())?
                    .connection_closed(connection.cast_unsigned())?,
            )?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_closeConnection<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    connection: jlong,
    error_code: jlong,
    reason: JByteArray<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?.close_connection(
                connection.cast_unsigned(),
                error_code.cast_unsigned(),
                &rust_bytes(env, &reason)?,
            )?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_writeAll<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    stream: jlong,
    bytes: JByteArray<'local>,
) {
    unowned
        .with_env(|env| -> BridgeResult<()> {
            let bytes = rust_bytes(env, &bytes)?;
            registry::get(handle.cast_unsigned())?.write_all(stream.cast_unsigned(), &bytes)?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_finishSend<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    stream: jlong,
) {
    unowned
        .with_env(|_| -> BridgeResult<()> {
            registry::get(handle.cast_unsigned())?.finish_send(stream.cast_unsigned())?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_readExact<'local>(
    unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    stream: jlong,
    length: jint,
) -> JByteArray<'local> {
    receive_bytes(unowned, handle, |endpoint| {
        let length = usize::try_from(length)
            .map_err(|_| crate::IrohError::new("read length must not be negative"))?;
        endpoint.read_exact(stream.cast_unsigned(), length)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_readToEnd<'local>(
    unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    stream: jlong,
    size_limit: jint,
) -> JByteArray<'local> {
    receive_bytes(unowned, handle, |endpoint| {
        let size_limit = usize::try_from(size_limit)
            .map_err(|_| crate::IrohError::new("size limit must not be negative"))?;
        endpoint.read_to_end(stream.cast_unsigned(), size_limit)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vafu_iroh_AndroidNativeBindings_awaitRouteChange<'local>(
    mut unowned: EnvUnowned<'local>,
    _this: JObject<'local>,
    handle: jlong,
    connection: jlong,
    previous: JString<'local>,
) -> JString<'local> {
    unowned
        .with_env(|env| -> BridgeResult<JString<'local>> {
            let previous = crate::Route::from_token(&previous.try_to_string(env)?)?;
            let route = registry::get(handle.cast_unsigned())?
                .await_route_change(connection.cast_unsigned(), previous)?
                .token();
            Ok(JString::from_str(env, route)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn receive_bytes<'local>(
    mut unowned: EnvUnowned<'local>,
    handle: jlong,
    receive: impl FnOnce(&crate::NativeEndpoint) -> Result<Vec<u8>, crate::IrohError>,
) -> JByteArray<'local> {
    unowned
        .with_env(|env| -> BridgeResult<JByteArray<'local>> {
            let endpoint = registry::get(handle.cast_unsigned())?;
            java_bytes(env, &receive(&endpoint)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn java_bytes<'local>(
    env: &mut jni::Env<'local>,
    bytes: &[u8],
) -> BridgeResult<JByteArray<'local>> {
    let array = JByteArray::new(env, bytes.len())?;
    let signed = bytes
        .iter()
        .map(|byte| byte.cast_signed())
        .collect::<Vec<_>>();
    array.set_region(env, 0, &signed)?;
    Ok(array)
}

fn rust_bytes(env: &jni::Env<'_>, bytes: &JByteArray<'_>) -> BridgeResult<Vec<u8>> {
    let mut signed = vec![0_i8; bytes.len(env)?];
    bytes.get_region(env, 0, &mut signed)?;
    Ok(signed.into_iter().map(i8::cast_unsigned).collect())
}

fn string_lines(env: &jni::Env<'_>, value: &JString<'_>) -> BridgeResult<Vec<String>> {
    Ok(value
        .try_to_string(env)?
        .lines()
        .filter(|line| !line.is_empty())
        .map(str::to_owned)
        .collect())
}

fn decode_hex(value: &str) -> BridgeResult<Vec<u8>> {
    if !value.len().is_multiple_of(2) {
        return Err(crate::IrohError::new("custom address hex has odd length").into());
    }
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let pair = std::str::from_utf8(pair)
                .map_err(|error| crate::IrohError::new(format!("invalid address hex: {error}")))?;
            u8::from_str_radix(pair, 16)
                .map_err(|error| crate::IrohError::new(format!("invalid address hex: {error}")))
                .map_err(BridgeError::from)
        })
        .collect()
}

fn jni_error(error: BridgeError) -> jni::errors::Error {
    match error {
        BridgeError::Jni(error) => error,
        BridgeError::Iroh(_) => unreachable!(),
    }
}
