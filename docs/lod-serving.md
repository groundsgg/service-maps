# Ordinary Stage Lobby LOD serving

Explicit operator configuration schedules LOD preparation after the current `lobby/mainlobby`
Stage pin has format 2 lighting. Normal map pushes and joins remain independent of optional LOD
availability. Clients never trigger generation; a server can discover a later READY result.

Canonical map, prelit transport, registry and generator digests identify an artifact. Identical
content reuses READY private output even across version bumps. One worker can run globally.
Failed attempts do not retry indefinitely. Unclaimed automatic selections survive outages
without rewriting audit timestamps or resetting reservations.

## Configuration

Set `grounds.maps.lod.enabled` and `grounds.maps.lod.auto.enabled` explicitly. Auto selection
requires `auto.environment=stage`, `auto.namespace=lobby`, `auto.name=mainlobby`, a stable
`auto.budget-id`, registry/generator SHA256 values and immutable `auto.worker-image`. The Helm
chart requires an existing `lod.node` and scoped derive Job access. Auto settings map to
`MAPS_LOD_AUTO_*` environment variables. Other maps/environments are ineligible.

Serving requires independent `grounds.maps.lod.serving.enabled`, stable `serving.budget-id`
and `serving.generation-budget-id`. An operator creates one `map_lod_delivery_budget` row
containing only the SHA256 of a stable server capability. Boot and discovery never mint/reset
credits. Background reconciliation publishes only READY private selections with the configured
auto provenance and exact current Stage pin. `serving.poll-interval` defaults to 30 seconds.
Keep finite pilot flags and public LOD publication off during ordinary serving.

Ordinary serving has no five-UUID allowlist or 30-minute expiry. Hosts require current
`grounds.lod.receive` permission at admission and serialization, using normal wildcard
precedence and scope. Private source/credit operations require the server capability and exact
artifact identity. Capabilities and reusable R2 URLs never enter Minecraft packets. Previously
cached Voxy terrain remains on the client after permission revocation.

## Initial measurement envelope

Generation shares at most two jobs and 1 GiB reserved bytes across pushes/restarts. Serving
shares at most 1 GiB payload reservations, 8192 payload grants, 10000 origin read reservations
and 1 GiB conservative origin-byte reservations. New maps never reset the counters. Per-UUID
pacing is 512 KiB per fixed second; the aggregate cap is 4 MiB per fixed second, at most 8 MiB
in any rolling second across API replicas/proxies. This is a measurement envelope, not an
assumed monthly monetary budget.

Compare actual source bytes, serialized bodies, reservations and provider traffic before
changing limits. Stop with `map_lod_delivery_budget.enabled=false` or the serving flag.
Payload permits expire within one second. Previously reserved origin downloads may drain once
within their original finite lease. Restart/re-enable never clears counters.
