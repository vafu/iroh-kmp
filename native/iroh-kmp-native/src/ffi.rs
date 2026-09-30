//! Stable C ABI consumed by Kotlin/Native on Apple platforms.

use std::cell::RefCell;
use std::ffi::{CStr, CString, c_char, c_void};
use std::slice;
use std::sync::Arc;

use iroh_base::CustomAddr;

use crate::registry;
use crate::runtime::{AddressLookupSink, EncodedEndpointData, PacketSink};
use crate::{IrohError, StreamPair};

type BytesCallback = unsafe extern "C" fn(*const u8, usize, *mut c_void);
type EndpointDataCallback =
    unsafe extern "C" fn(*const c_char, *const c_char, *const c_char, *const c_char, *mut c_void);
type PacketOfferCallback =
    unsafe extern "C" fn(*const u8, usize, *const u8, usize, *const u8, usize, *mut c_void) -> i32;
type AddressResolveCallback = unsafe extern "C" fn(u64, *const u8, usize, *mut c_void) -> i32;
type AddressCancelCallback = unsafe extern "C" fn(u64, *mut c_void);

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct FfiStreamPair {
    pub send: u64,
    pub receive: u64,
}

impl From<StreamPair> for FfiStreamPair {
    fn from(pair: StreamPair) -> Self {
        Self {
            send: pair.send,
            receive: pair.receive,
        }
    }
}

#[derive(Debug)]
struct FfiPacketSink {
    callback: PacketOfferCallback,
    context: usize,
}

impl PacketSink for FfiPacketSink {
    fn offer(
        &self,
        destination: &CustomAddr,
        source: Option<&CustomAddr>,
        bytes: &[u8],
    ) -> Result<bool, IrohError> {
        let (source_pointer, source_length) = source.map_or((std::ptr::null(), 0), |address| {
            (address.data().as_ptr(), address.data().len())
        });
        Ok(unsafe {
            (self.callback)(
                destination.data().as_ptr(),
                destination.data().len(),
                source_pointer,
                source_length,
                bytes.as_ptr(),
                bytes.len(),
                self.context as *mut c_void,
            ) != 0
        })
    }
}

#[derive(Debug)]
struct FfiAddressLookupSink {
    publish: EndpointDataCallback,
    resolve: AddressResolveCallback,
    cancel: AddressCancelCallback,
    context: usize,
}

impl AddressLookupSink for FfiAddressLookupSink {
    fn publish(&self, data: &iroh::address_lookup::EndpointData) -> Result<(), IrohError> {
        deliver_endpoint_data(data, self.publish, self.context as *mut c_void)
    }

    fn start_resolve(
        &self,
        request_id: u64,
        endpoint_id: iroh_base::EndpointId,
    ) -> Result<bool, IrohError> {
        let endpoint_id = endpoint_id.to_string();
        Ok(unsafe {
            (self.resolve)(
                request_id,
                endpoint_id.as_ptr(),
                endpoint_id.len(),
                self.context as *mut c_void,
            ) != 0
        })
    }

    fn cancel_resolve(&self, request_id: u64) -> Result<(), IrohError> {
        unsafe { (self.cancel)(request_id, self.context as *mut c_void) };
        Ok(())
    }
}

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_create() -> u64 {
    with_value(registry::create, 0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_add_alpn(handle: u64, alpn: *const u8, length: usize) -> i32 {
    with_status(|| registry::get(handle)?.add_alpn(unsafe { input_bytes(alpn, length)? }.to_vec()))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_set_relay_urls(handle: u64, relay_urls: *const c_char) -> i32 {
    with_status(|| registry::get(handle)?.set_relay_urls(unsafe { lines(relay_urls)? }))
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_register_custom_transport(
    handle: u64,
    transport_id: u64,
    packet_callback: Option<PacketOfferCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        registry::get(handle)?.register_custom_transport(
            transport_id,
            Arc::new(FfiPacketSink {
                callback: packet_callback
                    .ok_or_else(|| IrohError::new("missing packet callback"))?,
                context: context as usize,
            }),
        )
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_register_address_lookup(
    handle: u64,
    lookup_id: u64,
    publish_callback: Option<EndpointDataCallback>,
    resolve_callback: Option<AddressResolveCallback>,
    cancel_callback: Option<AddressCancelCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        registry::get(handle)?.register_address_lookup(
            lookup_id,
            Arc::new(FfiAddressLookupSink {
                publish: publish_callback
                    .ok_or_else(|| IrohError::new("missing address publish callback"))?,
                resolve: resolve_callback
                    .ok_or_else(|| IrohError::new("missing address resolve callback"))?,
                cancel: cancel_callback
                    .ok_or_else(|| IrohError::new("missing address cancel callback"))?,
                context: context as usize,
            }),
        )
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_emit_address_lookup_result(
    handle: u64,
    lookup_id: u64,
    request_id: u64,
    direct_addresses: *const c_char,
    relay_urls: *const c_char,
    custom_addresses: *const c_char,
    user_data: *const c_char,
) -> i32 {
    with_status(|| {
        let user_data = unsafe { optional_string(user_data)? };
        registry::get(handle)?.emit_address_lookup_result(
            lookup_id,
            request_id,
            &unsafe { lines(direct_addresses)? },
            &unsafe { lines(relay_urls)? },
            &unsafe { lines(custom_addresses)? },
            user_data.as_deref(),
        )
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_complete_address_lookup(
    handle: u64,
    lookup_id: u64,
    request_id: u64,
) -> i32 {
    with_status(|| registry::get(handle)?.complete_address_lookup(lookup_id, request_id))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_fail_address_lookup(
    handle: u64,
    lookup_id: u64,
    request_id: u64,
    message: *const c_char,
) -> i32 {
    with_status(|| {
        registry::get(handle)?.fail_address_lookup(lookup_id, request_id, unsafe {
            required_string(message, "address lookup failure")?
        })
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_set_custom_transport_addresses(
    handle: u64,
    transport_id: u64,
    addresses: *const c_char,
) -> i32 {
    with_status(|| {
        let addresses = unsafe { lines(addresses)? }
            .into_iter()
            .map(|value| decode_hex(&value))
            .collect::<Result<Vec<_>, _>>()?;
        registry::get(handle)?.set_local_addresses(transport_id, &addresses)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_bind(handle: u64) -> i32 {
    with_status(|| registry::get(handle)?.bind())
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_network_change(handle: u64) -> i32 {
    with_status(|| registry::get(handle)?.network_change())
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_destroy(handle: u64) -> i32 {
    with_status(|| registry::destroy(handle))
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_last_error() -> *const c_char {
    LAST_ERROR.with(|error| error.borrow().as_ptr())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_endpoint_id(
    handle: u64,
    callback: Option<BytesCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        deliver(
            registry::get(handle)?.endpoint_id().as_bytes(),
            callback,
            context,
        )
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_endpoint_data(
    handle: u64,
    callback: Option<EndpointDataCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        let address = registry::get(handle)?.endpoint_addr()?;
        let data = iroh::address_lookup::EndpointData::from(address);
        deliver_endpoint_data(
            &data,
            callback.ok_or_else(|| IrohError::new("missing endpoint data callback"))?,
            context,
        )
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_connect(
    handle: u64,
    endpoint_id: *const c_char,
    direct_addresses: *const c_char,
    relay_urls: *const c_char,
    custom_addresses: *const c_char,
    alpn: *const u8,
    alpn_length: usize,
) -> u64 {
    with_value(
        || {
            registry::get(handle)?.connect(
                &unsafe { required_string(endpoint_id, "endpoint ID")? },
                &unsafe { lines(direct_addresses)? },
                &unsafe { lines(relay_urls)? },
                &unsafe { lines(custom_addresses)? },
                unsafe { input_bytes(alpn, alpn_length)? },
            )
        },
        0,
    )
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_connection_remote_id(
    handle: u64,
    connection: u64,
    callback: Option<BytesCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        deliver(
            registry::get(handle)?
                .connection_remote_id(connection)?
                .as_bytes(),
            callback,
            context,
        )
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_open_bi(handle: u64, connection: u64) -> FfiStreamPair {
    with_value(
        || registry::get(handle)?.open_bi(connection).map(Into::into),
        FfiStreamPair::default(),
    )
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_accept_bi(handle: u64, connection: u64) -> FfiStreamPair {
    with_value(
        || registry::get(handle)?.accept_bi(connection).map(Into::into),
        FfiStreamPair::default(),
    )
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_notify_custom_transport_capacity(handle: u64, transport_id: u64) -> i32 {
    with_status(|| registry::get(handle)?.notify_transport_capacity(transport_id))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_submit_custom_packet(
    handle: u64,
    transport_id: u64,
    source: *const u8,
    source_length: usize,
    destination: *const u8,
    destination_length: usize,
    bytes: *const u8,
    length: usize,
) -> i32 {
    with_status(|| {
        let destination = if destination.is_null() {
            None
        } else {
            Some(unsafe { input_bytes(destination, destination_length)? })
        };
        registry::get(handle)?.submit_custom_packet(
            transport_id,
            unsafe { input_bytes(source, source_length)? },
            destination,
            unsafe { input_bytes(bytes, length)? }.to_vec(),
        )
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_send_datagram(
    handle: u64,
    connection: u64,
    bytes: *const u8,
    length: usize,
) -> i32 {
    with_status(|| {
        registry::get(handle)?
            .send_datagram(connection, unsafe { input_bytes(bytes, length)? }.to_vec())
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_send_datagram_wait(
    handle: u64,
    connection: u64,
    bytes: *const u8,
    length: usize,
) -> i32 {
    with_status(|| {
        registry::get(handle)?
            .send_datagram_wait(connection, unsafe { input_bytes(bytes, length)? }.to_vec())
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_read_datagram(
    handle: u64,
    connection: u64,
    callback: Option<BytesCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        deliver(
            &registry::get(handle)?.read_datagram(connection)?,
            callback,
            context,
        )
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_connection_closed(
    handle: u64,
    connection: u64,
    callback: Option<BytesCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        deliver(
            registry::get(handle)?
                .connection_closed(connection)?
                .as_bytes(),
            callback,
            context,
        )
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_close_connection(
    handle: u64,
    connection: u64,
    error_code: u64,
    reason: *const u8,
    reason_length: usize,
) -> i32 {
    with_status(|| {
        registry::get(handle)?.close_connection(connection, error_code, unsafe {
            input_bytes(reason, reason_length)?
        })
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_write_all(
    handle: u64,
    stream: u64,
    bytes: *const u8,
    length: usize,
) -> i32 {
    with_status(|| registry::get(handle)?.write_all(stream, unsafe { input_bytes(bytes, length)? }))
}

#[unsafe(no_mangle)]
pub extern "C" fn iroh_kmp_finish_send(handle: u64, stream: u64) -> i32 {
    with_status(|| registry::get(handle)?.finish_send(stream))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_read_exact(
    handle: u64,
    stream: u64,
    length: usize,
    callback: Option<BytesCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        deliver(
            &registry::get(handle)?.read_exact(stream, length)?,
            callback,
            context,
        )
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_read_to_end(
    handle: u64,
    stream: u64,
    size_limit: usize,
    callback: Option<BytesCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        deliver(
            &registry::get(handle)?.read_to_end(stream, size_limit)?,
            callback,
            context,
        )
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh_kmp_await_route_change(
    handle: u64,
    connection: u64,
    previous: *const c_char,
    callback: Option<BytesCallback>,
    context: *mut c_void,
) -> i32 {
    with_status(|| {
        let previous =
            crate::Route::from_token(&unsafe { required_string(previous, "previous route")? })?;
        let route = registry::get(handle)?
            .await_route_change(connection, previous)?
            .token();
        deliver(route.as_bytes(), callback, context)
    })
}

unsafe fn lines(pointer: *const c_char) -> Result<Vec<String>, IrohError> {
    Ok(unsafe { optional_string(pointer)? }
        .unwrap_or_default()
        .lines()
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect())
}

fn decode_hex(value: &str) -> Result<Vec<u8>, IrohError> {
    if !value.len().is_multiple_of(2) {
        return Err(IrohError::new("custom address hex has odd length"));
    }
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let pair = std::str::from_utf8(pair)
                .map_err(|error| IrohError::new(format!("invalid address hex: {error}")))?;
            u8::from_str_radix(pair, 16)
                .map_err(|error| IrohError::new(format!("invalid address hex: {error}")))
        })
        .collect()
}

fn deliver_endpoint_data(
    data: &iroh::address_lookup::EndpointData,
    callback: EndpointDataCallback,
    context: *mut c_void,
) -> Result<(), IrohError> {
    let encoded = EncodedEndpointData::from(data);
    let direct = ffi_string(encoded.direct_addresses)?;
    let relays = ffi_string(encoded.relay_urls)?;
    let custom = ffi_string(encoded.custom_addresses)?;
    let user_data = encoded.user_data.map(ffi_string).transpose()?;
    unsafe {
        callback(
            direct.as_ptr(),
            relays.as_ptr(),
            custom.as_ptr(),
            user_data
                .as_ref()
                .map_or(std::ptr::null(), |value| value.as_ptr()),
            context,
        );
    }
    Ok(())
}

fn ffi_string(value: String) -> Result<CString, IrohError> {
    CString::new(value).map_err(|_| IrohError::new("endpoint data contains a NUL byte"))
}

fn deliver(
    bytes: &[u8],
    callback: Option<BytesCallback>,
    context: *mut c_void,
) -> Result<(), IrohError> {
    let callback = callback.ok_or_else(|| IrohError::new("missing byte callback"))?;
    unsafe { callback(bytes.as_ptr(), bytes.len(), context) };
    Ok(())
}

unsafe fn required_string(pointer: *const c_char, name: &str) -> Result<String, IrohError> {
    unsafe { optional_string(pointer)? }
        .filter(|value| !value.is_empty())
        .ok_or_else(|| IrohError::new(format!("missing {name}")))
}

unsafe fn optional_string(pointer: *const c_char) -> Result<Option<String>, IrohError> {
    if pointer.is_null() {
        return Ok(None);
    }
    unsafe { CStr::from_ptr(pointer) }
        .to_str()
        .map(|value| Some(value.to_owned()))
        .map_err(|error| IrohError::new(format!("invalid UTF-8 input: {error}")))
}

unsafe fn input_bytes<'a>(pointer: *const u8, length: usize) -> Result<&'a [u8], IrohError> {
    if length == 0 {
        return Ok(&[]);
    }
    if pointer.is_null() {
        return Err(IrohError::new("missing byte input"));
    }
    Ok(unsafe { slice::from_raw_parts(pointer, length) })
}

fn with_status(operation: impl FnOnce() -> Result<(), IrohError>) -> i32 {
    match operation() {
        Ok(()) => 0,
        Err(error) => fail(&error),
    }
}

fn with_value<T: Copy>(operation: impl FnOnce() -> Result<T, IrohError>, fallback: T) -> T {
    match operation() {
        Ok(value) => value,
        Err(error) => {
            set_error(&error);
            fallback
        }
    }
}

fn fail(error: &IrohError) -> i32 {
    set_error(error);
    -1
}

fn set_error(error: &IrohError) {
    let sanitized = error.message().replace('\0', " ");
    LAST_ERROR.with(|last| {
        *last.borrow_mut() = CString::new(sanitized).expect("NUL bytes were removed");
    });
}
