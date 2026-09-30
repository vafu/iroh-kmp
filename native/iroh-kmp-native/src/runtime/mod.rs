//! Native endpoint contract.

mod address_lookup;
mod connection;
mod endpoint;

use std::collections::HashMap;
use std::fmt;
use std::sync::atomic::{AtomicBool, AtomicU64};
use std::sync::{Arc, Mutex};

use crate::transport::{InboundPacket, PacketTransport};
use iroh::address_lookup::EndpointData;
use iroh::endpoint::{Connection, RecvStream, SendStream};
use iroh::{Endpoint, SecretKey};
use iroh_base::{CustomAddr, EndpointId, TransportAddr};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

use address_lookup::PlatformAddressLookup;

const CLOSED_REASON: &[u8] = b"platform endpoint closed";

/// One process-local Iroh endpoint exposed to Kotlin.
pub struct NativeEndpoint {
    runtime: Runtime,
    secret_key: SecretKey,
    alpns: Mutex<Vec<Vec<u8>>>,
    relay_urls: Mutex<Vec<String>>,
    platform_address_lookups: Mutex<HashMap<u64, PlatformAddressLookup>>,
    custom_transports: Mutex<HashMap<u64, RegisteredTransport>>,
    endpoint: Mutex<Option<Endpoint>>,
    connections: Mutex<HashMap<u64, Connection>>,
    send_streams: tokio::sync::Mutex<HashMap<u64, SendStream>>,
    receive_streams: tokio::sync::Mutex<HashMap<u64, RecvStream>>,
    next_resource_id: AtomicU64,
    closed: AtomicBool,
}

struct RegisteredTransport {
    transport: Arc<PacketTransport>,
    sender: Arc<endpoint::PlatformPacketSender>,
    inbound: mpsc::Sender<InboundPacket>,
}

/// Handles for both halves of one bidirectional QUIC stream.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct StreamPair {
    /// Native sending-stream handle.
    pub send: u64,
    /// Native receiving-stream handle.
    pub receive: u64,
}

/// Currently selected Iroh path.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Route {
    /// No connected path is currently selected.
    Disconnected,
    /// Direct IP path.
    Direct,
    /// A Kotlin-provided custom transport.
    Custom(u64),
    /// Configured relay.
    Relay,
}

pub(crate) trait AddressLookupSink: fmt::Debug + Send + Sync {
    fn publish(&self, data: &EndpointData) -> Result<(), IrohError>;

    fn start_resolve(&self, request_id: u64, endpoint_id: EndpointId) -> Result<bool, IrohError>;

    fn cancel_resolve(&self, request_id: u64) -> Result<(), IrohError>;
}

pub(crate) trait PacketSink: fmt::Debug + Send + Sync {
    fn offer(
        &self,
        destination: &CustomAddr,
        source: Option<&CustomAddr>,
        bytes: &[u8],
    ) -> Result<bool, IrohError>;
}

pub(crate) struct EncodedEndpointData {
    pub(crate) direct_addresses: String,
    pub(crate) relay_urls: String,
    pub(crate) custom_addresses: String,
    pub(crate) user_data: Option<String>,
}

impl From<&EndpointData> for EncodedEndpointData {
    fn from(data: &EndpointData) -> Self {
        let direct_addresses = data
            .ip_addrs()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join("\n");
        let relay_urls = data
            .relay_urls()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join("\n");
        let custom_addresses = data
            .addrs()
            .filter_map(|address| match address {
                TransportAddr::Custom(address) => Some(address.to_string()),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join("\n");
        Self {
            direct_addresses,
            relay_urls,
            custom_addresses,
            user_data: data.user_data().map(ToString::to_string),
        }
    }
}

impl Route {
    pub(crate) fn token(self) -> String {
        match self {
            Self::Disconnected => "disconnected".to_owned(),
            Self::Direct => "direct".to_owned(),
            Self::Custom(transport_id) => format!("custom:{transport_id}"),
            Self::Relay => "relay".to_owned(),
        }
    }

    pub(crate) fn from_token(token: &str) -> Result<Self, IrohError> {
        match token {
            "disconnected" => Ok(Self::Disconnected),
            "direct" => Ok(Self::Direct),
            "relay" => Ok(Self::Relay),
            value => value
                .strip_prefix("custom:")
                .ok_or_else(|| IrohError::new(format!("invalid route token: {value}")))?
                .parse::<u64>()
                .map(Self::Custom)
                .map_err(|error| IrohError::new(format!("invalid custom route: {error}"))),
        }
    }
}

/// Failure produced by the native Iroh binding.
#[derive(Debug)]
pub struct IrohError {
    message: String,
}

impl IrohError {
    pub(super) fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }

    /// Human-readable description suitable for a platform exception.
    #[must_use]
    pub fn message(&self) -> &str {
        &self.message
    }
}

impl fmt::Display for IrohError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for IrohError {}
