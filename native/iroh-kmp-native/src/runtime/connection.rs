use std::sync::atomic::Ordering;

use bytes::Bytes;
use futures::StreamExt;
use iroh::TransportAddr;
use iroh::endpoint::{Connection, RecvStream, SendStream};

use super::endpoint::{parse_endpoint_addr, poisoned};
use super::{CLOSED_REASON, IrohError, NativeEndpoint, Route, StreamPair};

impl NativeEndpoint {
    pub(crate) fn connect(
        &self,
        remote_endpoint_id: &str,
        direct_addresses: &[String],
        relay_urls: &[String],
        custom_addresses: &[String],
        alpn: &[u8],
    ) -> Result<u64, IrohError> {
        if alpn.is_empty() {
            return Err(IrohError::new("ALPN must not be empty"));
        }
        let remote = parse_endpoint_addr(
            remote_endpoint_id,
            direct_addresses,
            relay_urls,
            custom_addresses,
        )?;
        let endpoint = self.endpoint()?;
        let connection = self
            .runtime
            .block_on(endpoint.connect(remote, alpn))
            .map_err(|error| IrohError::new(format!("Iroh connection failed: {error}")))?;
        if self.closed.load(Ordering::Acquire) {
            connection.close(0_u32.into(), CLOSED_REASON);
            return Err(IrohError::new("Iroh endpoint is closed"));
        }
        let handle = self.next_resource_id();
        self.connections
            .lock()
            .map_err(poisoned)?
            .insert(handle, connection);
        Ok(handle)
    }

    pub(crate) fn connection_remote_id(&self, connection: u64) -> Result<String, IrohError> {
        Ok(self.connection(connection)?.remote_id().to_string())
    }

    pub(crate) fn open_bi(&self, connection: u64) -> Result<StreamPair, IrohError> {
        let connection = self.connection(connection)?;
        let (send, receive) = self
            .runtime
            .block_on(connection.open_bi())
            .map_err(|error| {
                IrohError::new(format!("failed to open bidirectional stream: {error}"))
            })?;
        Ok(self.store_stream_pair(send, receive))
    }

    pub(crate) fn accept_bi(&self, connection: u64) -> Result<StreamPair, IrohError> {
        let connection = self.connection(connection)?;
        let (send, receive) = self
            .runtime
            .block_on(connection.accept_bi())
            .map_err(|error| {
                IrohError::new(format!("failed to accept bidirectional stream: {error}"))
            })?;
        Ok(self.store_stream_pair(send, receive))
    }

    pub(crate) fn send_datagram(&self, connection: u64, bytes: Vec<u8>) -> Result<(), IrohError> {
        self.connection(connection)?
            .send_datagram(Bytes::from(bytes))
            .map_err(|error| IrohError::new(format!("datagram send failed: {error}")))
    }

    pub(crate) fn send_datagram_wait(
        &self,
        connection: u64,
        bytes: Vec<u8>,
    ) -> Result<(), IrohError> {
        let connection = self.connection(connection)?;
        self.runtime
            .block_on(connection.send_datagram_wait(Bytes::from(bytes)))
            .map_err(|error| IrohError::new(format!("datagram send failed: {error}")))
    }

    pub(crate) fn read_datagram(&self, connection: u64) -> Result<Vec<u8>, IrohError> {
        let connection = self.connection(connection)?;
        self.runtime
            .block_on(connection.read_datagram())
            .map(|bytes| bytes.to_vec())
            .map_err(|error| IrohError::new(format!("datagram receive failed: {error}")))
    }

    pub(crate) fn connection_closed(&self, connection: u64) -> Result<String, IrohError> {
        let connection = self.connection(connection)?;
        Ok(self.runtime.block_on(connection.closed()).to_string())
    }

    pub(crate) fn close_connection(
        &self,
        connection: u64,
        error_code: u64,
        reason: &[u8],
    ) -> Result<(), IrohError> {
        let error_code = iroh::endpoint::VarInt::try_from(error_code)
            .map_err(|_| IrohError::new("QUIC error code exceeds 62 bits"))?;
        if let Some(connection) = self
            .connections
            .lock()
            .map_err(poisoned)?
            .remove(&connection)
        {
            connection.close(error_code, reason);
        }
        Ok(())
    }

    pub(crate) fn await_route_change(
        &self,
        connection: u64,
        previous: Route,
    ) -> Result<Route, IrohError> {
        let connection = self.connection(connection)?;
        self.runtime.block_on(async {
            let mut paths = connection.paths_stream();
            while let Some(paths) = paths.next().await {
                let route = selected_route(&paths);
                if route != previous {
                    return Ok(route);
                }
            }
            Err(IrohError::new("Iroh connection closed"))
        })
    }

    pub(crate) fn write_all(&self, stream: u64, data: &[u8]) -> Result<(), IrohError> {
        self.runtime.block_on(async {
            let mut streams = self.send_streams.lock().await;
            let stream = streams
                .get_mut(&stream)
                .ok_or_else(|| IrohError::new("unknown Iroh send stream"))?;
            stream
                .write_all(data)
                .await
                .map_err(|error| IrohError::new(format!("stream write failed: {error}")))
        })
    }

    pub(crate) fn finish_send(&self, stream: u64) -> Result<(), IrohError> {
        self.runtime.block_on(async {
            let mut stream = self
                .send_streams
                .lock()
                .await
                .remove(&stream)
                .ok_or_else(|| IrohError::new("unknown Iroh send stream"))?;
            stream
                .finish()
                .map_err(|error| IrohError::new(format!("stream finish failed: {error}")))
        })
    }

    pub(crate) fn read_exact(&self, stream: u64, length: usize) -> Result<Vec<u8>, IrohError> {
        self.runtime.block_on(async {
            let mut streams = self.receive_streams.lock().await;
            let stream = streams
                .get_mut(&stream)
                .ok_or_else(|| IrohError::new("unknown Iroh receive stream"))?;
            let mut bytes = vec![0_u8; length];
            stream
                .read_exact(&mut bytes)
                .await
                .map_err(|error| IrohError::new(format!("stream read failed: {error}")))?;
            Ok(bytes)
        })
    }

    pub(crate) fn read_to_end(&self, stream: u64, size_limit: usize) -> Result<Vec<u8>, IrohError> {
        self.runtime.block_on(async {
            let mut stream = self
                .receive_streams
                .lock()
                .await
                .remove(&stream)
                .ok_or_else(|| IrohError::new("unknown Iroh receive stream"))?;
            stream
                .read_to_end(size_limit)
                .await
                .map_err(|error| IrohError::new(format!("stream read failed: {error}")))
        })
    }

    fn connection(&self, handle: u64) -> Result<Connection, IrohError> {
        self.ensure_open()?;
        self.connections
            .lock()
            .map_err(poisoned)?
            .get(&handle)
            .cloned()
            .ok_or_else(|| IrohError::new("unknown Iroh connection"))
    }

    fn store_stream_pair(&self, send: SendStream, receive: RecvStream) -> StreamPair {
        let send_handle = self.next_resource_id();
        let receive_handle = self.next_resource_id();
        self.runtime.block_on(async {
            self.send_streams.lock().await.insert(send_handle, send);
            self.receive_streams
                .lock()
                .await
                .insert(receive_handle, receive);
        });
        StreamPair {
            send: send_handle,
            receive: receive_handle,
        }
    }
}

fn selected_route(paths: &iroh::endpoint::PathList<'_>) -> Route {
    let Some(path) = paths.iter().find(iroh::endpoint::Path::is_selected) else {
        return Route::Disconnected;
    };
    if path.remote_addr().is_ip() {
        Route::Direct
    } else if path.remote_addr().is_relay() {
        Route::Relay
    } else if let TransportAddr::Custom(address) = path.remote_addr() {
        Route::Custom(address.id())
    } else {
        Route::Disconnected
    }
}
