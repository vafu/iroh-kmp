# Packet transport

This module adapts a pair of bounded packet queues to Iroh's custom transport
interfaces. Platform code owns the physical link and feeds complete QUIC packets
into `InboundPacket`; Iroh emits `OutboundPacket` values for the platform to send.

The adapter never interprets application messages. Backpressure is propagated
through `Poll::Pending` and `ChannelPacketSender::notify_capacity`.
