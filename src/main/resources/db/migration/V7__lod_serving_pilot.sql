-- Each explicit pilot gets a finite lifetime and a shared irreversible budget. No seed/admission on boot.
CREATE TABLE map_lod_serving_run (
    id UUID PRIMARY KEY,
    artifact_attempt UUID NOT NULL REFERENCES map_lod(attempt),
    token_sha256 TEXT NOT NULL CHECK (token_sha256 ~ '^[a-f0-9]{64}$'),
    allowed_players UUID[] NOT NULL CHECK (cardinality(allowed_players) BETWEEN 1 AND 5),
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    payload_grants_reserved INT NOT NULL DEFAULT 0 CHECK (payload_grants_reserved BETWEEN 0 AND 8192),
    payload_reserved BIGINT NOT NULL DEFAULT 0 CHECK (payload_reserved BETWEEN 0 AND 1073741824),
    remote_reads_reserved INT NOT NULL DEFAULT 0 CHECK (remote_reads_reserved BETWEEN 0 AND 10000),
    remote_bytes_reserved BIGINT NOT NULL DEFAULT 0 CHECK (remote_bytes_reserved BETWEEN 0 AND 1073741824),
    CHECK (expires_at > starts_at AND expires_at <= starts_at + interval '30 minutes')
);
-- A second measurement run requires an explicit stop of the prior run; no accidental stacking.
CREATE UNIQUE INDEX map_lod_one_enabled_serving_run ON map_lod_serving_run ((true)) WHERE enabled;
-- One-second allocation windows are global by UUID, including two proxies, reconnects and processes.
-- At most five UUIDs => 2.5MiB/window, <=5MiB over any rolling second, below aggregate 8MiB/s.
CREATE TABLE map_lod_serving_window (
    run_id UUID NOT NULL REFERENCES map_lod_serving_run(id),
    player UUID NOT NULL,
    window_second BIGINT NOT NULL,
    bytes_reserved INT NOT NULL CHECK (bytes_reserved BETWEEN 1 AND 524288),
    PRIMARY KEY (run_id,player,window_second)
);
-- A successful debit is never replayed as another reusable grant. Lost responses waste credits.
CREATE TABLE map_lod_serving_debit (
    run_id UUID NOT NULL REFERENCES map_lod_serving_run(id),
    request_id UUID NOT NULL,
    player UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('PAYLOAD','REMOTE')),
    amount INT NOT NULL CHECK (amount BETWEEN 1 AND 134217728),
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    PRIMARY KEY (run_id,request_id)
);
