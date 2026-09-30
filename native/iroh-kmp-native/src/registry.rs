use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use crate::{IrohError, NativeEndpoint};

type Endpoints = Mutex<HashMap<u64, Arc<NativeEndpoint>>>;

static ENDPOINTS: OnceLock<Endpoints> = OnceLock::new();
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

pub(crate) fn create() -> Result<u64, IrohError> {
    let endpoint = Arc::new(NativeEndpoint::new()?);
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    endpoints()
        .lock()
        .map_err(poisoned)?
        .insert(handle, endpoint);
    Ok(handle)
}

pub(crate) fn get(handle: u64) -> Result<Arc<NativeEndpoint>, IrohError> {
    endpoints()
        .lock()
        .map_err(poisoned)?
        .get(&handle)
        .cloned()
        .ok_or_else(|| IrohError::new("invalid native Iroh endpoint handle"))
}

pub(crate) fn destroy(handle: u64) -> Result<(), IrohError> {
    let endpoint = endpoints()
        .lock()
        .map_err(poisoned)?
        .remove(&handle)
        .ok_or_else(|| IrohError::new("invalid native Iroh endpoint handle"))?;
    endpoint.close();
    Ok(())
}

fn endpoints() -> &'static Endpoints {
    ENDPOINTS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn poisoned<T>(error: std::sync::PoisonError<T>) -> IrohError {
    let message = format!("native endpoint registry lock poisoned: {error}");
    drop(error);
    IrohError::new(message)
}

#[cfg(test)]
mod tests {
    use super::*;
    use iroh_base::CustomAddr;

    use crate::runtime::PacketSink;

    #[derive(Debug)]
    struct AcceptAllPackets;

    impl PacketSink for AcceptAllPackets {
        fn offer(
            &self,
            _destination: &CustomAddr,
            _source: Option<&CustomAddr>,
            _bytes: &[u8],
        ) -> Result<bool, IrohError> {
            Ok(true)
        }
    }

    #[test]
    fn handles_are_unique_and_destroyed_handles_are_rejected() {
        let first = create().unwrap();
        let second = create().unwrap();
        let retained = get(first).unwrap();
        assert_ne!(first, second);
        assert_eq!(retained.endpoint_id().len(), 64);
        destroy(first).unwrap();
        assert!(get(first).is_err());
        assert!(
            retained
                .endpoint_addr()
                .unwrap_err()
                .to_string()
                .contains("closed")
        );
        destroy(second).unwrap();
    }

    #[test]
    fn rejects_duplicate_custom_transport_ids() {
        let handle = create().unwrap();
        let endpoint = get(handle).unwrap();
        endpoint
            .register_custom_transport(7, Arc::new(AcceptAllPackets))
            .unwrap();

        let error = endpoint
            .register_custom_transport(7, Arc::new(AcceptAllPackets))
            .unwrap_err();

        assert!(error.to_string().contains("duplicate custom transport ID"));
        destroy(handle).unwrap();
    }
}
