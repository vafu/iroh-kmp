//! Packet-oriented custom transport primitives for Iroh.

mod endpoint;

#[cfg(test)]
mod test;

use std::sync::{Arc, Mutex};

#[cfg(test)]
use futures::task::AtomicWaker;
use iroh::endpoint::transports::CustomSender;
use iroh_base::CustomAddr;
use tokio::sync::mpsc;

/// Maximum queued packets in either direction before producers are backpressured.
pub const PACKET_QUEUE_CAPACITY: usize = 256;

/// One packet emitted by Iroh for a custom-transport address.
#[derive(Debug, Eq, PartialEq)]
#[cfg(test)]
pub struct OutboundPacket {
    /// Opaque address that should receive the packet.
    pub destination: CustomAddr,
    /// Optional local address selected by Iroh.
    pub source: Option<CustomAddr>,
    /// One complete QUIC packet.
    pub bytes: Vec<u8>,
}

/// One packet received from a custom-transport address.
#[derive(Debug, Eq, PartialEq)]
pub struct InboundPacket {
    /// Opaque address that sent the packet.
    pub source: CustomAddr,
    /// Optional local address that received the packet.
    pub destination: Option<CustomAddr>,
    /// One complete QUIC packet.
    pub bytes: Vec<u8>,
}

/// Sends Iroh packets into a bounded Tokio channel.
#[derive(Debug)]
#[cfg(test)]
pub struct ChannelPacketSender {
    transport_id: u64,
    sender: mpsc::Sender<OutboundPacket>,
    capacity_waker: AtomicWaker,
}

#[cfg(test)]
impl ChannelPacketSender {
    /// Wraps the sending half of the platform packet queue.
    #[must_use]
    pub fn new(transport_id: u64, sender: mpsc::Sender<OutboundPacket>) -> Self {
        Self {
            transport_id,
            sender,
            capacity_waker: AtomicWaker::new(),
        }
    }

    /// Notifies Iroh that the queue consumer freed at least one slot.
    pub fn notify_capacity(&self) {
        self.capacity_waker.wake();
    }
}

impl endpoint::PacketTransport {
    /// Creates an Iroh custom endpoint from packet I/O and its initial addresses.
    #[must_use]
    pub fn new(
        local_addresses: Vec<CustomAddr>,
        sender: Arc<dyn CustomSender>,
        inbound: mpsc::Receiver<InboundPacket>,
    ) -> Self {
        Self {
            local_addresses: n0_watcher::Watchable::new(local_addresses),
            sender,
            inbound: Mutex::new(Some(inbound)),
        }
    }

    /// Replaces the addresses advertised by this transport.
    pub fn set_local_addresses(&self, addresses: Vec<CustomAddr>) {
        let _ = self.local_addresses.set(addresses);
    }
}

pub use endpoint::PacketTransport;
