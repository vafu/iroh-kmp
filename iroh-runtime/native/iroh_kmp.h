#ifndef IROH_KMP_H
#define IROH_KMP_H

#include <stddef.h>
#include <stdint.h>

typedef void (*iroh_kmp_bytes_callback)(const uint8_t *, size_t, void *);
typedef void (*iroh_kmp_endpoint_data_callback)(
    const char *direct_addresses, const char *relay_urls,
    const char *custom_addresses, const char *user_data,
    void *context);
typedef int32_t (*iroh_kmp_packet_offer_callback)(
    const uint8_t *destination, size_t destination_length,
    const uint8_t *source, size_t source_length,
    const uint8_t *packet, size_t packet_length,
    void *context);
typedef int32_t (*iroh_kmp_address_resolve_callback)(
    uint64_t request_id, const uint8_t *endpoint_id, size_t endpoint_id_length,
    void *context);
typedef void (*iroh_kmp_address_cancel_callback)(
    uint64_t request_id, void *context);

typedef struct iroh_kmp_stream_pair {
    uint64_t send;
    uint64_t receive;
} iroh_kmp_stream_pair;

uint64_t iroh_kmp_create(void);
int32_t iroh_kmp_add_alpn(
    uint64_t handle, const uint8_t *alpn, size_t length);
int32_t iroh_kmp_set_relay_urls(
    uint64_t handle, const char *relay_urls);
int32_t iroh_kmp_register_custom_transport(
    uint64_t handle, uint64_t transport_id,
    iroh_kmp_packet_offer_callback, void *context);
int32_t iroh_kmp_register_address_lookup(
    uint64_t handle, uint64_t lookup_id,
    iroh_kmp_endpoint_data_callback,
    iroh_kmp_address_resolve_callback,
    iroh_kmp_address_cancel_callback,
    void *context);
int32_t iroh_kmp_emit_address_lookup_result(
    uint64_t handle, uint64_t lookup_id, uint64_t request_id,
    const char *direct_addresses, const char *relay_urls,
    const char *custom_addresses, const char *user_data);
int32_t iroh_kmp_complete_address_lookup(
    uint64_t handle, uint64_t lookup_id, uint64_t request_id);
int32_t iroh_kmp_fail_address_lookup(
    uint64_t handle, uint64_t lookup_id, uint64_t request_id,
    const char *message);
int32_t iroh_kmp_set_custom_transport_addresses(
    uint64_t handle, uint64_t transport_id, const char *addresses);
int32_t iroh_kmp_bind(uint64_t handle);
int32_t iroh_kmp_network_change(uint64_t handle);
int32_t iroh_kmp_destroy(uint64_t handle);
const char *iroh_kmp_last_error(void);
int32_t iroh_kmp_endpoint_id(
    uint64_t handle, iroh_kmp_bytes_callback, void *context);
int32_t iroh_kmp_endpoint_data(
    uint64_t handle, iroh_kmp_endpoint_data_callback, void *context);
uint64_t iroh_kmp_connect(
    uint64_t handle, const char *endpoint_id,
    const char *direct_addresses, const char *relay_urls,
    const char *custom_addresses, const uint8_t *alpn, size_t alpn_length);
int32_t iroh_kmp_connection_remote_id(
    uint64_t handle, uint64_t connection,
    iroh_kmp_bytes_callback, void *context);
iroh_kmp_stream_pair iroh_kmp_open_bi(
    uint64_t handle, uint64_t connection);
iroh_kmp_stream_pair iroh_kmp_accept_bi(
    uint64_t handle, uint64_t connection);
int32_t iroh_kmp_send_datagram(
    uint64_t handle, uint64_t connection,
    const uint8_t *bytes, size_t length);
int32_t iroh_kmp_send_datagram_wait(
    uint64_t handle, uint64_t connection,
    const uint8_t *bytes, size_t length);
int32_t iroh_kmp_read_datagram(
    uint64_t handle, uint64_t connection,
    iroh_kmp_bytes_callback, void *context);
int32_t iroh_kmp_connection_closed(
    uint64_t handle, uint64_t connection,
    iroh_kmp_bytes_callback, void *context);
int32_t iroh_kmp_close_connection(
    uint64_t handle, uint64_t connection, uint64_t error_code,
    const uint8_t *reason, size_t reason_length);
int32_t iroh_kmp_await_route_change(
    uint64_t handle, uint64_t connection, const char *previous_route,
    iroh_kmp_bytes_callback, void *context);

int32_t iroh_kmp_write_all(
    uint64_t handle, uint64_t stream,
    const uint8_t *bytes, size_t length);
int32_t iroh_kmp_finish_send(uint64_t handle, uint64_t stream);
int32_t iroh_kmp_read_exact(
    uint64_t handle, uint64_t stream, size_t length,
    iroh_kmp_bytes_callback, void *context);
int32_t iroh_kmp_read_to_end(
    uint64_t handle, uint64_t stream, size_t size_limit,
    iroh_kmp_bytes_callback, void *context);

int32_t iroh_kmp_notify_custom_transport_capacity(
    uint64_t handle, uint64_t transport_id);
int32_t iroh_kmp_submit_custom_packet(
    uint64_t handle, uint64_t transport_id,
    const uint8_t *source, size_t source_length,
    const uint8_t *destination, size_t destination_length,
    const uint8_t *bytes, size_t length);

#endif
