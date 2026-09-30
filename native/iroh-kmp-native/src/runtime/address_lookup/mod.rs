use std::collections::HashMap;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::{SystemTime, UNIX_EPOCH};

use futures::channel::mpsc::{self, UnboundedReceiver, UnboundedSender};
use futures::stream::{BoxStream, Stream};
use iroh::address_lookup::{
    AddressLookup, EndpointData, EndpointInfo, Error as AddressLookupError,
    Item as AddressLookupItem,
};
use iroh_base::EndpointId;

use super::{AddressLookupSink, IrohError};

#[cfg(test)]
mod test;

const PROVENANCE: &str = "kotlin";

#[derive(Clone)]
pub(crate) struct PlatformAddressLookup {
    shared: Arc<Shared>,
}

struct Shared {
    sink: Arc<dyn AddressLookupSink>,
    next_request_id: AtomicU64,
    requests: Mutex<HashMap<u64, PendingRequest>>,
}

struct PendingRequest {
    endpoint_id: EndpointId,
    sender: UnboundedSender<Result<AddressLookupItem, AddressLookupError>>,
}

struct LookupStream {
    request_id: u64,
    receiver: UnboundedReceiver<Result<AddressLookupItem, AddressLookupError>>,
    shared: Arc<Shared>,
}

impl PlatformAddressLookup {
    pub(crate) fn new(sink: Arc<dyn AddressLookupSink>) -> Self {
        Self {
            shared: Arc::new(Shared {
                sink,
                next_request_id: AtomicU64::new(1),
                requests: Mutex::new(HashMap::new()),
            }),
        }
    }

    pub(crate) fn emit(&self, request_id: u64, data: EndpointData) {
        let Ok(requests) = self.shared.requests.lock() else {
            return;
        };
        let Some(request) = requests.get(&request_id) else {
            return;
        };
        let last_updated = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .ok()
            .and_then(|duration| u64::try_from(duration.as_micros()).ok());
        let item = AddressLookupItem::new(
            EndpointInfo::from_parts(request.endpoint_id, data),
            PROVENANCE,
            last_updated,
        );
        let _ = request.sender.unbounded_send(Ok(item));
    }

    pub(crate) fn complete(&self, request_id: u64) {
        self.remove(request_id);
    }

    pub(crate) fn fail(&self, request_id: u64, message: String) {
        let Some(request) = self.remove(request_id) else {
            return;
        };
        let _ = request
            .sender
            .unbounded_send(Err(AddressLookupError::from_err(
                PROVENANCE,
                IrohError::new(message),
            )));
    }

    pub(crate) fn close(&self) {
        let request_ids = self
            .shared
            .requests
            .lock()
            .map(|requests| requests.keys().copied().collect::<Vec<_>>())
            .unwrap_or_default();
        for request_id in request_ids {
            self.cancel(request_id);
        }
    }

    fn remove(&self, request_id: u64) -> Option<PendingRequest> {
        self.shared.requests.lock().ok()?.remove(&request_id)
    }

    fn cancel(&self, request_id: u64) {
        if self.remove(request_id).is_some() {
            let _ = self.shared.sink.cancel_resolve(request_id);
        }
    }
}

impl std::fmt::Debug for PlatformAddressLookup {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.debug_struct("PlatformAddressLookup").finish()
    }
}

impl AddressLookup for PlatformAddressLookup {
    fn publish(&self, data: &EndpointData) {
        let _ = self.shared.sink.publish(data);
    }

    fn resolve(
        &self,
        endpoint_id: EndpointId,
    ) -> Option<BoxStream<'static, Result<AddressLookupItem, AddressLookupError>>> {
        let request_id = self.shared.next_request_id.fetch_add(1, Ordering::Relaxed);
        let (sender, receiver) = mpsc::unbounded();
        self.shared.requests.lock().ok()?.insert(
            request_id,
            PendingRequest {
                endpoint_id,
                sender,
            },
        );
        let stream = LookupStream {
            request_id,
            receiver,
            shared: Arc::clone(&self.shared),
        };
        match self.shared.sink.start_resolve(request_id, endpoint_id) {
            Ok(true) => Some(Box::pin(stream)),
            Ok(false) => {
                self.remove(request_id);
                None
            }
            Err(error) => {
                self.fail(request_id, error.to_string());
                Some(Box::pin(stream))
            }
        }
    }
}

impl Stream for LookupStream {
    type Item = Result<AddressLookupItem, AddressLookupError>;

    fn poll_next(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        Pin::new(&mut self.receiver).poll_next(context)
    }
}

impl Drop for LookupStream {
    fn drop(&mut self) {
        let lookup = PlatformAddressLookup {
            shared: Arc::clone(&self.shared),
        };
        lookup.cancel(self.request_id);
    }
}
