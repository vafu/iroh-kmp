use std::io::{self, IoSliceMut};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};

#[cfg(test)]
use iroh::endpoint::transports::Transmit;
use iroh::endpoint::transports::{CustomEndpoint, CustomSender, CustomTransport, RecvInfo};
use iroh_base::CustomAddr;
use n0_watcher::Watchable;
use tokio::sync::mpsc;

use super::InboundPacket;
#[cfg(test)]
use super::{ChannelPacketSender, OutboundPacket};

#[derive(Debug)]
pub struct PacketTransport {
    pub(super) local_addresses: Watchable<Vec<CustomAddr>>,
    pub(super) sender: Arc<dyn CustomSender>,
    pub(super) inbound: Mutex<Option<mpsc::Receiver<InboundPacket>>>,
}

impl CustomTransport for PacketTransport {
    fn bind(&self) -> io::Result<Box<dyn CustomEndpoint>> {
        let inbound = self
            .inbound
            .lock()
            .map_err(|_| io::Error::other("packet transport lock poisoned"))?
            .take()
            .ok_or_else(|| {
                io::Error::new(io::ErrorKind::AlreadyExists, "transport already bound")
            })?;
        Ok(Box::new(PacketEndpoint {
            local_addresses: self.local_addresses.clone(),
            sender: Arc::clone(&self.sender),
            inbound,
        }))
    }
}

struct PacketEndpoint {
    local_addresses: Watchable<Vec<CustomAddr>>,
    sender: Arc<dyn CustomSender>,
    inbound: mpsc::Receiver<InboundPacket>,
}

impl std::fmt::Debug for PacketEndpoint {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PacketEndpoint")
            .field("local_addresses", &self.local_addresses.get())
            .finish_non_exhaustive()
    }
}

impl CustomEndpoint for PacketEndpoint {
    fn watch_local_addrs(&self) -> n0_watcher::Direct<Vec<CustomAddr>> {
        self.local_addresses.watch()
    }

    fn create_sender(&self) -> Arc<dyn CustomSender> {
        Arc::clone(&self.sender)
    }

    fn poll_recv(
        &mut self,
        context: &mut Context<'_>,
        buffers: &mut [IoSliceMut<'_>],
        metadata: &mut [noq_udp::RecvMeta],
        receive_info: &mut [RecvInfo],
    ) -> Poll<io::Result<usize>> {
        assert_eq!(buffers.len(), metadata.len());
        assert_eq!(buffers.len(), receive_info.len());
        if buffers.is_empty() {
            return Poll::Ready(Ok(0));
        }

        let mut packets = Vec::with_capacity(buffers.len());
        match self
            .inbound
            .poll_recv_many(context, &mut packets, buffers.len())
        {
            Poll::Pending => return Poll::Pending,
            Poll::Ready(0) => {
                return Poll::Ready(Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "custom packet input closed",
                )));
            }
            Poll::Ready(_) => {}
        }

        let mut received = 0;
        for (index, packet) in packets.into_iter().enumerate() {
            if packet.bytes.len() > buffers[index].len() {
                continue;
            }
            buffers[index][..packet.bytes.len()].copy_from_slice(&packet.bytes);
            metadata[index].len = packet.bytes.len();
            metadata[index].stride = packet.bytes.len();
            receive_info[index] = RecvInfo::new(packet.source, packet.destination);
            received += 1;
        }
        Poll::Ready(Ok(received))
    }
}

#[cfg(test)]
impl CustomSender for ChannelPacketSender {
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
                "packet transport does not support segmented transmits",
            )));
        }
        self.capacity_waker.register(context.waker());
        match self.sender.try_reserve() {
            Ok(permit) => {
                permit.send(OutboundPacket {
                    destination: destination.clone(),
                    source: source.cloned(),
                    bytes: transmit.contents.to_vec(),
                });
                Poll::Ready(Ok(()))
            }
            Err(mpsc::error::TrySendError::Full(())) => Poll::Pending,
            Err(mpsc::error::TrySendError::Closed(())) => Poll::Ready(Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                "custom packet output closed",
            ))),
        }
    }
}
