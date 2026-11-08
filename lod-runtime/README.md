# Optional Lobby LOD pilot

The `gg.grounds:service-maps-lod-runtime` JVM library serves an explicitly selected immutable
Lobby through LSS20. It depends on `service-maps-lod-format`. The Minestom adapter stays out
of the Native API classpath; the API shares the pure format library. The existing Grounds publishing convention gives both libraries the Maps
release version. The tag-driven library workflow prepares publication; adding this code does
not create a tag, publish packages or activate a deployment.

The host must supply the actual loaded instance, canonical map SHA256, loaded prelit transport
SHA256 and generator SHA256. The adapter recomputes the host's block/biome registry identity
and rejects mismatches. It supports the pinned Minestom `2026.10.25b-26.3-grounds`, exact client
MC data version, and an LSS20 client declaring voxel+Zstd capabilities. Native Distant Horizons
and legacy LSS dialects are separate adapters and are not implemented here.

```kotlin
val control = LodPilotHttpClient(origin, runId, capability, actualCanonicalSha256)
val pilot = MinestomLodAdapter.install(
    eventNode, loadedInstance,
    LodExpectation(actualCanonicalSha256, actualPrelitSha256, registrySha256, generatorSha256),
    control, allowedPlayerUuids, cacheDirectory,
)
// Close before tearing down the host; the global guardian survives queued packets.
pilot.close()
```

The host decides when this immutable source is eligible. The Lobby integration additionally
requires the actual loaded Stage `lobby/mainlobby` version13, verified prelit transport and an
explicit default-off environment flag. Vanilla/nonallowlisted players do not initialize LOD.
The source is not inferred from an Overworld dimension name or a proxy-local player lookup.

## Resource and cost behavior

- One shared source initialization per adapter/Pod; no worldgen, no source retries, no per-column R2
  reads or HEADs. Valid disk content avoids the origin download. Initial metadata reserves a
  conservative remote envelope even when a disk hit leaves that envelope unused.
- One archive <=128MiB. Declared raw column bytes and retained Zstd column data are each capped
  at64MiB; each retained column at64KiB. Tile/ZIP/hash limits from `lod-format` apply before use.
  Decoding happens on one background loader, never on a game tick.
- The dedicated cache directory holds one `terrain.zip` plus a fixed `terrain.part` protected
  by an exclusive OS file lock. Startup recovers abandoned work after process death. Hash-mismatched
  cached data is discarded; an interrupted transfer only removes work it created. A failed
  initialization stays failed instead of retrying on every join. Reinitialization still needs
  another durable server-side debit; restarts do not create a fresh shared budget.
- Up to five UUIDs. Atomic shared server grants apply across backends/proxies/rejoins. Credit
  lifetime is measured from monotonic request-start, never response arrival. Missing/expired
  grants stop LOD; normal map loading and joins do not call this control path.
- HTTP reservations run outside the locks used by game/network event callbacks. Bounded input
  queues hold at most4 frames per UUID; parsed requests at most1024 positions. Each complete
  want declaration replaces older work; an empty declaration immediately withdraws it. At most ONE
  outgoing LOD frame per UUID can be queued locally, including overlapping reconnects. A
  following internal barrier is always cancelled before encoding; it acknowledges queue
  consumption without sending bytes, making buffer retries and final cancellation observable.
- Before serialization, the guardian checks the current connection, instance identity, epoch,
  source, negotiation generation and credit lifetime. Committed world lifecycle events
  publish new generations even on an A-to-B-to-A hop while control HTTP is blocked. Local rolling application-byte pacing is512KiB/second/connection;
  the shared DB uses512KiB/UUID/discrete-second windows. Across five UUIDs its rolling upper
  envelope is5MiB, below the8MiB pilot aggregate target, subject to ordinary socket buffering.
  One bounded frame can remain in the socket writer; this is not a physical link shaper.
- Before each SessionConfig, a guarded `minecraft:register` advertises the inbound LSS channels,
  so Fabric can send terrain requests. Registration and config share the same permit and FIFO
  acknowledgement; cancelling either prevents configuration completion.
- Every frame reserves its payload plus512 bytes for conservative Minecraft framing/compression
  and the small channel advertisement.
  503/transport/parse failures disable this optional gate without polling. Per-UUID429/window
  pacing waits1s and stops after8 consecutive refusals; valid credits on other UUIDs continue.
  A hard local30min lifetime bounds even a front door returning only429.
  TCP/IP, encryption and proxy egress still require network measurement. `stats()` reports
  AUTHORIZED payload bodies and guardian rejections, not billed or confirmed socket bytes.
- Pilot source URLs are canonical same-origin one-use API paths. The server capability stays
  in a private header; no R2 keys, signed source URL or capability goes to a Minecraft client.
  Redirects, Range and HEAD are unsupported. Revoking the durable run prevents further grants;
  previously reserved payload grants expire within1s, active source streams within their bound.

The adapter exclusively owns its four outbound LSS channels while active. Do not install a
second LSS sender beside it. Required validation still includes a real Voxy+LSS profile,
two-proxy/two-backend transitions, actual network byte counts and Tick/Join measurements.

## Local verification

`./gradlew :lod-runtime:test` exercises control paths, deadlines, shared/disk cache, wire
fixtures, epoch cancellation, overlapping connections and real Minestom event carriers.
The vendored RegionSummaryWire is unchanged MIT upstream code; see `UPSTREAM.md`.
