use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use futures::StreamExt;
use iroh::SecretKey;
use iroh::address_lookup::{AddressLookup, EndpointData};
use iroh_base::{EndpointId, TransportAddr};

use super::PlatformAddressLookup;
use crate::runtime::{AddressLookupSink, IrohError};

#[derive(Debug, Default)]
struct RecordingSink {
    handles_endpoint: AtomicBool,
    published: Mutex<Vec<EndpointData>>,
    started: Mutex<Vec<(u64, EndpointId)>>,
    cancelled: Mutex<Vec<u64>>,
}

impl AddressLookupSink for RecordingSink {
    fn publish(&self, data: &EndpointData) -> Result<(), IrohError> {
        self.published.lock().unwrap().push(data.clone());
        Ok(())
    }

    fn start_resolve(&self, request_id: u64, endpoint_id: EndpointId) -> Result<bool, IrohError> {
        self.started.lock().unwrap().push((request_id, endpoint_id));
        Ok(self.handles_endpoint.load(Ordering::Relaxed))
    }

    fn cancel_resolve(&self, request_id: u64) -> Result<(), IrohError> {
        self.cancelled.lock().unwrap().push(request_id);
        Ok(())
    }
}

#[tokio::test]
async fn streams_platform_results_into_iroh() {
    let sink = Arc::new(RecordingSink::default());
    sink.handles_endpoint.store(true, Ordering::Relaxed);
    let lookup = PlatformAddressLookup::new(sink.clone());
    let remote = SecretKey::generate().public();
    let data = EndpointData::new(vec![TransportAddr::Ip("127.0.0.1:4242".parse().unwrap())]);

    lookup.publish(&data);
    let mut results = lookup.resolve(remote).unwrap();
    lookup.emit(1, data.clone());
    lookup.complete(1);

    let result = results.next().await.unwrap().unwrap();
    assert_eq!(result.endpoint_id(), remote);
    assert_eq!(&result.endpoint_info().data, &data);
    assert!(results.next().await.is_none());
    assert_eq!(sink.published.lock().unwrap().as_slice(), &[data]);
    assert_eq!(sink.started.lock().unwrap().as_slice(), &[(1, remote)]);
    assert!(sink.cancelled.lock().unwrap().is_empty());
}

#[test]
fn dropping_result_stream_cancels_platform_work() {
    let sink = Arc::new(RecordingSink::default());
    sink.handles_endpoint.store(true, Ordering::Relaxed);
    let lookup = PlatformAddressLookup::new(sink.clone());

    let results = lookup.resolve(SecretKey::generate().public()).unwrap();
    drop(results);

    assert_eq!(sink.cancelled.lock().unwrap().as_slice(), &[1]);
}

#[test]
fn unsupported_endpoint_returns_no_stream() {
    let sink = Arc::new(RecordingSink::default());
    let lookup = PlatformAddressLookup::new(sink.clone());

    assert!(lookup.resolve(SecretKey::generate().public()).is_none());
    assert!(sink.cancelled.lock().unwrap().is_empty());
}
