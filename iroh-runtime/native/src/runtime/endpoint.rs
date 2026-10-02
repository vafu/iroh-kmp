use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::task::{Context, Poll};

use crate::transport::{InboundPacket, PACKET_QUEUE_CAPACITY, PacketTransport};
use futures::task::AtomicWaker;
use iroh::address_lookup::{EndpointData, UserData};
use iroh::endpoint::presets;
use iroh::endpoint::transports::{CustomSender, Transmit};
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayMode, RelayUrl, SecretKey, TransportAddr};
use iroh_base::CustomAddr;
use tokio::sync::mpsc;

use super::{
    AddressLookupSink, CLOSED_REASON, IrohError, NativeEndpoint, PacketSink, PlatformAddressLookup,
    RegisteredTransport,
};

impl NativeEndpoint {
    /// Creates an unbound endpoint configuration.
    pub(crate) fn new() -> Result<Self, IrohError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("iroh-kmp")
            .build()
            .map_err(|error| IrohError::new(format!("failed to create Iroh runtime: {error}")))?;
        Ok(Self {
            runtime,
            secret_key: SecretKey::generate(),
            alpns: std::sync::Mutex::new(Vec::new()),
            relay_urls: std::sync::Mutex::new(Vec::new()),
            platform_address_lookups: std::sync::Mutex::new(HashMap::new()),
            custom_transports: std::sync::Mutex::new(HashMap::new()),
            endpoint: std::sync::Mutex::new(None),
            connections: std::sync::Mutex::new(HashMap::new()),
            send_streams: tokio::sync::Mutex::new(HashMap::new()),
            receive_streams: tokio::sync::Mutex::new(HashMap::new()),
            next_resource_id: std::sync::atomic::AtomicU64::new(1),
            closed: std::sync::atomic::AtomicBool::new(false),
        })
    }

    pub(crate) fn add_alpn(&self, alpn: Vec<u8>) -> Result<(), IrohError> {
        self.ensure_configurable()?;
        if alpn.is_empty() {
            return Err(IrohError::new("ALPN must not be empty"));
        }
        self.alpns.lock().map_err(poisoned)?.push(alpn);
        Ok(())
    }

    pub(crate) fn set_relay_urls(&self, relay_urls: Vec<String>) -> Result<(), IrohError> {
        self.ensure_configurable()?;
        for relay_url in &relay_urls {
            relay_url
                .parse::<RelayUrl>()
                .map_err(|error| IrohError::new(format!("invalid relay URL: {error}")))?;
        }
        *self.relay_urls.lock().map_err(poisoned)? = relay_urls;
        Ok(())
    }

    pub(crate) fn register_custom_transport(
        &self,
        transport_id: u64,
        sink: Arc<dyn PacketSink>,
    ) -> Result<(), IrohError> {
        self.ensure_configurable()?;
        let mut transports = self.custom_transports.lock().map_err(poisoned)?;
        if transports.contains_key(&transport_id) {
            return Err(IrohError::new(format!(
                "duplicate custom transport ID: {transport_id}",
            )));
        }
        let (inbound, inbound_receiver) = mpsc::channel(PACKET_QUEUE_CAPACITY);
        let sender = Arc::new(PlatformPacketSender::new(transport_id, sink));
        let transport = Arc::new(PacketTransport::new(
            Vec::new(),
            sender.clone(),
            inbound_receiver,
        ));
        transports.insert(
            transport_id,
            RegisteredTransport {
                transport,
                sender,
                inbound,
            },
        );
        Ok(())
    }

    pub(crate) fn register_address_lookup(
        &self,
        lookup_id: u64,
        sink: Arc<dyn AddressLookupSink>,
    ) -> Result<(), IrohError> {
        self.ensure_configurable()?;
        let mut lookups = self.platform_address_lookups.lock().map_err(poisoned)?;
        if lookups.contains_key(&lookup_id) {
            return Err(IrohError::new(format!(
                "duplicate address lookup ID: {lookup_id}",
            )));
        }
        lookups.insert(lookup_id, PlatformAddressLookup::new(sink));
        Ok(())
    }

    pub(crate) fn emit_address_lookup_result(
        &self,
        lookup_id: u64,
        request_id: u64,
        direct_addresses: &[String],
        relay_urls: &[String],
        custom_addresses: &[String],
        user_data: Option<&str>,
    ) -> Result<(), IrohError> {
        let data = parse_endpoint_data(direct_addresses, relay_urls, custom_addresses, user_data)?;
        if let Some(lookup) = self
            .platform_address_lookups
            .lock()
            .map_err(poisoned)?
            .get(&lookup_id)
        {
            lookup.emit(request_id, data);
        }
        Ok(())
    }

    pub(crate) fn complete_address_lookup(
        &self,
        lookup_id: u64,
        request_id: u64,
    ) -> Result<(), IrohError> {
        if let Some(lookup) = self
            .platform_address_lookups
            .lock()
            .map_err(poisoned)?
            .get(&lookup_id)
        {
            lookup.complete(request_id);
        }
        Ok(())
    }

    pub(crate) fn fail_address_lookup(
        &self,
        lookup_id: u64,
        request_id: u64,
        message: String,
    ) -> Result<(), IrohError> {
        if let Some(lookup) = self
            .platform_address_lookups
            .lock()
            .map_err(poisoned)?
            .get(&lookup_id)
        {
            lookup.fail(request_id, message);
        }
        Ok(())
    }

    /// Replaces the addresses advertised by a registered custom transport.
    ///
    /// # Errors
    ///
    /// Returns an error when `transport_id` is not registered or internal state is poisoned.
    pub fn set_local_addresses(
        &self,
        transport_id: u64,
        addresses: &[Vec<u8>],
    ) -> Result<(), IrohError> {
        let transports = self.custom_transports.lock().map_err(poisoned)?;
        let registered = transports.get(&transport_id).ok_or_else(|| {
            IrohError::new(format!("unknown custom transport ID: {transport_id}"))
        })?;
        registered.transport.set_local_addresses(
            addresses
                .iter()
                .map(|address| CustomAddr::from_parts(transport_id, address))
                .collect(),
        );
        Ok(())
    }

    pub(crate) fn bind(&self) -> Result<(), IrohError> {
        self.ensure_configurable()?;
        let custom_transports = self
            .custom_transports
            .lock()
            .map_err(poisoned)?
            .values()
            .map(|registered| Arc::clone(&registered.transport))
            .collect::<Vec<_>>();
        let address_lookups = self
            .platform_address_lookups
            .lock()
            .map_err(poisoned)?
            .values()
            .cloned()
            .collect::<Vec<_>>();
        let alpns = self.alpns.lock().map_err(poisoned)?.clone();
        let relays = self
            .relay_urls
            .lock()
            .map_err(poisoned)?
            .iter()
            .map(|url| url.parse::<RelayUrl>())
            .collect::<Result<Vec<_>, _>>()
            .map_err(|error| IrohError::new(format!("invalid relay URL: {error}")))?;
        let relay_mode = if relays.is_empty() {
            RelayMode::Disabled
        } else {
            RelayMode::custom(relays)
        };
        let endpoint = self
            .runtime
            .block_on(async {
                let mut builder = Endpoint::builder(presets::Minimal)
                    .secret_key(self.secret_key.clone())
                    .alpns(alpns)
                    .relay_mode(relay_mode);
                for transport in custom_transports {
                    builder = builder.add_custom_transport(transport);
                }
                for lookup in address_lookups {
                    builder = builder.address_lookup(lookup);
                }
                builder.bind().await
            })
            .map_err(|error| IrohError::new(format!("failed to bind Iroh endpoint: {error}")))?;
        *self.endpoint.lock().map_err(poisoned)? = Some(endpoint);
        Ok(())
    }

    #[must_use]
    pub fn endpoint_id(&self) -> String {
        self.secret_key.public().to_string()
    }

    pub(crate) fn endpoint_addr(&self) -> Result<EndpointAddr, IrohError> {
        Ok(self.endpoint()?.addr())
    }

    pub(crate) fn network_change(&self) -> Result<(), IrohError> {
        let endpoint = self.endpoint()?;
        self.runtime.block_on(endpoint.network_change());
        Ok(())
    }

    /// Wakes Iroh after a custom sender becomes ready to accept another packet.
    ///
    /// # Errors
    ///
    /// Returns an error when `transport_id` is not registered or internal state is poisoned.
    pub fn notify_transport_capacity(&self, transport_id: u64) -> Result<(), IrohError> {
        let transports = self.custom_transports.lock().map_err(poisoned)?;
        let registered = transports.get(&transport_id).ok_or_else(|| {
            IrohError::new(format!("unknown custom transport ID: {transport_id}"))
        })?;
        registered.sender.notify_capacity();
        Ok(())
    }

    /// Offers one packet received by a custom transport to Iroh.
    ///
    /// # Errors
    ///
    /// Returns an error when the transport is unknown, closed, full, or internal state is
    /// poisoned.
    pub fn submit_custom_packet(
        &self,
        transport_id: u64,
        source: &[u8],
        destination: Option<&[u8]>,
        bytes: Vec<u8>,
    ) -> Result<(), IrohError> {
        let transports = self.custom_transports.lock().map_err(poisoned)?;
        let registered = transports.get(&transport_id).ok_or_else(|| {
            IrohError::new(format!("unknown custom transport ID: {transport_id}"))
        })?;
        registered
            .inbound
            .try_send(InboundPacket {
                source: CustomAddr::from_parts(transport_id, source),
                destination: destination
                    .map(|address| CustomAddr::from_parts(transport_id, address)),
                bytes,
            })
            .map_err(|error| IrohError::new(format!("custom packet input failed: {error}")))
    }

    pub fn close(&self) {
        if self.closed.swap(true, Ordering::AcqRel) {
            return;
        }
        if let Ok(mut connections) = self.connections.lock() {
            for connection in connections.drain().map(|(_, connection)| connection) {
                connection.close(0_u32.into(), CLOSED_REASON);
            }
        }
        self.runtime.block_on(async {
            self.send_streams.lock().await.clear();
            self.receive_streams.lock().await.clear();
        });
        if let Ok(mut endpoint) = self.endpoint.lock()
            && let Some(endpoint) = endpoint.take()
        {
            self.runtime.block_on(endpoint.close());
        }
        if let Ok(lookups) = self.platform_address_lookups.lock() {
            for lookup in lookups.values() {
                lookup.close();
            }
        }
    }

    pub(super) fn endpoint(&self) -> Result<Endpoint, IrohError> {
        self.ensure_open()?;
        self.endpoint
            .lock()
            .map_err(poisoned)?
            .clone()
            .ok_or_else(|| IrohError::new("Iroh endpoint is not bound"))
    }

    pub(super) fn next_resource_id(&self) -> u64 {
        self.next_resource_id.fetch_add(1, Ordering::Relaxed)
    }

    fn ensure_configurable(&self) -> Result<(), IrohError> {
        self.ensure_open()?;
        if self.endpoint.lock().map_err(poisoned)?.is_some() {
            Err(IrohError::new("Iroh endpoint is already bound"))
        } else {
            Ok(())
        }
    }

    pub(super) fn ensure_open(&self) -> Result<(), IrohError> {
        if self.closed.load(Ordering::Acquire) {
            Err(IrohError::new("Iroh endpoint is closed"))
        } else {
            Ok(())
        }
    }
}

impl Drop for NativeEndpoint {
    fn drop(&mut self) {
        self.close();
    }
}

#[derive(Debug)]
pub(super) struct PlatformPacketSender {
    transport_id: u64,
    sink: Arc<dyn PacketSink>,
    capacity_waker: AtomicWaker,
}

impl PlatformPacketSender {
    fn new(transport_id: u64, sink: Arc<dyn PacketSink>) -> Self {
        Self {
            transport_id,
            sink,
            capacity_waker: AtomicWaker::new(),
        }
    }

    fn notify_capacity(&self) {
        self.capacity_waker.wake();
    }
}

impl CustomSender for PlatformPacketSender {
    fn is_valid_send_addr(&self, address: &CustomAddr) -> bool {
        address.id() == self.transport_id
    }

    fn poll_send(
        &self,
        context: &mut Context<'_>,
        destination: &CustomAddr,
        source: Option<&CustomAddr>,
        transmit: &Transmit<'_>,
    ) -> Poll<io::Result<()>> {
        if !self.is_valid_send_addr(destination) {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "address belongs to another custom transport",
            )));
        }
        if transmit
            .segment_size
            .is_some_and(|size| size != transmit.contents.len())
        {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "custom transport does not support segmented transmits",
            )));
        }
        self.capacity_waker.register(context.waker());
        match self.sink.offer(destination, source, transmit.contents) {
            Ok(true) => Poll::Ready(Ok(())),
            Ok(false) => Poll::Pending,
            Err(error) => Poll::Ready(Err(io::Error::other(error))),
        }
    }
}

pub(super) fn parse_endpoint_addr(
    endpoint_id: &str,
    direct_addresses: &[String],
    relay_urls: &[String],
    custom_addresses: &[String],
) -> Result<EndpointAddr, IrohError> {
    let endpoint_id = endpoint_id
        .parse::<EndpointId>()
        .map_err(|error| IrohError::new(format!("invalid remote endpoint ID: {error}")))?;
    Ok(EndpointAddr::from_parts(
        endpoint_id,
        parse_transport_addrs(direct_addresses, relay_urls, custom_addresses)?,
    ))
}

fn parse_endpoint_data(
    direct_addresses: &[String],
    relay_urls: &[String],
    custom_addresses: &[String],
    user_data: Option<&str>,
) -> Result<EndpointData, IrohError> {
    let mut data = EndpointData::new(parse_transport_addrs(
        direct_addresses,
        relay_urls,
        custom_addresses,
    )?);
    if let Some(user_data) = user_data {
        data.set_user_data(Some(user_data.parse::<UserData>().map_err(|error| {
            IrohError::new(format!("invalid address lookup user data: {error}"))
        })?));
    }
    Ok(data)
}

fn parse_transport_addrs(
    direct_addresses: &[String],
    relay_urls: &[String],
    custom_addresses: &[String],
) -> Result<Vec<TransportAddr>, IrohError> {
    let mut addresses =
        Vec::with_capacity(direct_addresses.len() + relay_urls.len() + custom_addresses.len());
    for address in direct_addresses {
        addresses.push(TransportAddr::Ip(address.parse::<SocketAddr>().map_err(
            |error| IrohError::new(format!("invalid direct address {address}: {error}")),
        )?));
    }
    for relay_url in relay_urls {
        addresses.push(TransportAddr::Relay(
            relay_url
                .parse::<RelayUrl>()
                .map_err(|error| IrohError::new(format!("invalid relay URL: {error}")))?,
        ));
    }
    for address in custom_addresses {
        addresses.push(TransportAddr::Custom(
            address.parse::<CustomAddr>().map_err(|error| {
                IrohError::new(format!("invalid custom address {address}: {error}"))
            })?,
        ));
    }
    Ok(addresses)
}

pub(super) fn poisoned<T>(error: std::sync::PoisonError<T>) -> IrohError {
    let message = format!("Iroh endpoint state lock poisoned: {error}");
    drop(error);
    IrohError::new(message)
}
