use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use iroh::endpoint::presets;
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayMode, SecretKey, TransportAddr};
use iroh_base::CustomAddr;
use tokio::sync::{Mutex, mpsc};

use super::{
    ChannelPacketSender, InboundPacket, OutboundPacket, PACKET_QUEUE_CAPACITY, PacketTransport,
};

const TEST_TRANSPORT_ID: u64 = 7;

#[tokio::test]
async fn carries_an_iroh_connection_over_the_custom_transport() {
    const ALPN: &[u8] = b"iroh-kmp/custom-transport-test";
    let first_key = SecretKey::generate();
    let second_key = SecretKey::generate();
    let (first_transport, first_port) = test_transport(first_key.public());
    let (second_transport, second_port) = test_transport(second_key.public());
    let first = Endpoint::builder(presets::Minimal)
        .secret_key(first_key.clone())
        .alpns(vec![ALPN.to_vec()])
        .relay_mode(RelayMode::Disabled)
        .clear_ip_transports()
        .add_custom_transport(first_transport)
        .bind()
        .await
        .unwrap();
    let second = Endpoint::builder(presets::Minimal)
        .secret_key(second_key.clone())
        .alpns(vec![ALPN.to_vec()])
        .relay_mode(RelayMode::Disabled)
        .clear_ip_transports()
        .add_custom_transport(second_transport)
        .bind()
        .await
        .unwrap();

    let forward = tokio::spawn(forward_packets(
        Arc::clone(&first_port),
        Arc::clone(&second_port),
        first_key.public(),
    ));
    let reverse = tokio::spawn(forward_packets(
        second_port,
        first_port,
        second_key.public(),
    ));
    let server_endpoint = second.clone();
    let server = tokio::spawn(async move {
        let connection = server_endpoint.accept().await.unwrap().await.unwrap();
        connection.read_datagram().await.unwrap()
    });
    let remote = EndpointAddr::from_parts(
        second_key.public(),
        [TransportAddr::Custom(test_address(second_key.public()))],
    );
    let connection = tokio::time::timeout(Duration::from_secs(5), first.connect(remote, ALPN))
        .await
        .unwrap()
        .unwrap();
    connection
        .send_datagram(Bytes::from_static(b"encrypted over packet link"))
        .unwrap();

    let received = tokio::time::timeout(Duration::from_secs(5), server)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(received.as_ref(), b"encrypted over packet link");
    connection.close(0_u32.into(), b"done");
    forward.abort();
    reverse.abort();
    let _ = forward.await;
    let _ = reverse.await;
    first.close().await;
    second.close().await;
}

#[tokio::test]
async fn discovers_a_direct_path_alongside_the_packet_link() {
    const ALPN: &[u8] = b"iroh-kmp/path-migration-test";
    let first_key = SecretKey::generate();
    let second_key = SecretKey::generate();
    let (first_transport, first_port) = test_transport(first_key.public());
    let (second_transport, second_port) = test_transport(second_key.public());
    let first = Endpoint::builder(presets::Minimal)
        .secret_key(first_key.clone())
        .alpns(vec![ALPN.to_vec()])
        .relay_mode(RelayMode::Disabled)
        .add_custom_transport(first_transport)
        .bind()
        .await
        .unwrap();
    let second = Endpoint::builder(presets::Minimal)
        .secret_key(second_key.clone())
        .alpns(vec![ALPN.to_vec()])
        .relay_mode(RelayMode::Disabled)
        .add_custom_transport(second_transport)
        .bind()
        .await
        .unwrap();

    let forward = tokio::spawn(forward_packets(
        Arc::clone(&first_port),
        Arc::clone(&second_port),
        first_key.public(),
    ));
    let reverse = tokio::spawn(forward_packets(
        second_port,
        first_port,
        second_key.public(),
    ));
    let server_endpoint = second.clone();
    let server =
        tokio::spawn(async move { server_endpoint.accept().await.unwrap().await.unwrap() });
    let remote = EndpointAddr::from_parts(
        second_key.public(),
        [TransportAddr::Custom(test_address(second_key.public()))],
    );
    let connection = tokio::time::timeout(Duration::from_secs(5), first.connect(remote, ALPN))
        .await
        .unwrap()
        .unwrap();
    let server_connection = server.await.unwrap();

    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if connection
                .paths()
                .iter()
                .any(|path| path.remote_addr().is_ip())
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("Iroh did not discover a direct IP path");

    connection.close(0_u32.into(), b"done");
    server_connection.close(0_u32.into(), b"done");
    forward.abort();
    reverse.abort();
    let _ = forward.await;
    let _ = reverse.await;
    first.close().await;
    second.close().await;
}

async fn forward_packets(
    source: Arc<TestLink>,
    destination: Arc<TestLink>,
    source_id: iroh::EndpointId,
) {
    let mut outbound = source.outbound.lock().await;
    while let Some(packet) = outbound.recv().await {
        source.sender.notify_capacity();
        if destination
            .inbound
            .send(InboundPacket {
                source: test_address(source_id),
                destination: None,
                bytes: packet.bytes,
            })
            .await
            .is_err()
        {
            break;
        }
    }
}

struct TestLink {
    outbound: Mutex<mpsc::Receiver<OutboundPacket>>,
    sender: Arc<ChannelPacketSender>,
    inbound: mpsc::Sender<InboundPacket>,
}

fn test_transport(endpoint: EndpointId) -> (Arc<PacketTransport>, Arc<TestLink>) {
    let (outbound_sender, outbound) = mpsc::channel(PACKET_QUEUE_CAPACITY);
    let (inbound, inbound_receiver) = mpsc::channel(PACKET_QUEUE_CAPACITY);
    let sender = Arc::new(ChannelPacketSender::new(TEST_TRANSPORT_ID, outbound_sender));
    let transport = Arc::new(PacketTransport::new(
        vec![test_address(endpoint)],
        sender.clone(),
        inbound_receiver,
    ));
    (
        transport,
        Arc::new(TestLink {
            outbound: Mutex::new(outbound),
            sender,
            inbound,
        }),
    )
}

fn test_address(endpoint: EndpointId) -> CustomAddr {
    CustomAddr::from_parts(TEST_TRANSPORT_ID, endpoint.as_bytes())
}
