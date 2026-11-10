-- Reservations are permanent: restart, retry, or a new run cannot reset a pilot budget.
-- No run is admitted by migration or an application startup. An operator must select an input.
CREATE TABLE map_lod_budget (
    id TEXT PRIMARY KEY,
    jobs_reserved INT NOT NULL DEFAULT 0 CHECK (jobs_reserved BETWEEN 0 AND 2),
    bytes_reserved BIGINT NOT NULL DEFAULT 0 CHECK (bytes_reserved BETWEEN 0 AND 1073741824)
);
CREATE TABLE map_lod_run (
    id UUID PRIMARY KEY,
    budget_id TEXT NOT NULL REFERENCES map_lod_budget(id),
    environment TEXT NOT NULL,
    namespace TEXT NOT NULL,
    name TEXT NOT NULL,
    version INT NOT NULL CHECK (version > 0),
    map_bundle_sha256 TEXT NOT NULL CHECK (map_bundle_sha256 ~ '^[a-f0-9]{64}$'),
    source_bundle_sha256 TEXT NOT NULL CHECK (source_bundle_sha256 ~ '^[a-f0-9]{64}$'),
    registry_sha256 TEXT NOT NULL CHECK (registry_sha256 ~ '^[a-f0-9]{64}$'),
    generator_sha256 TEXT NOT NULL CHECK (generator_sha256 ~ '^[a-f0-9]{64}$'),
    worker_image TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by TEXT NOT NULL,
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '30 minutes')
);
CREATE TABLE map_lod (
    attempt UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES map_lod_run(id),
    identity_sha256 TEXT NOT NULL,
    trust TEXT NOT NULL CHECK (trust IN ('FIRST_PARTY', 'UNTRUSTED')),
    map_bundle_sha256 TEXT NOT NULL,
    source_bundle_sha256 TEXT NOT NULL,
    registry_sha256 TEXT NOT NULL,
    generator_sha256 TEXT NOT NULL,
    worker_image TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('GENERATING', 'READY', 'FAILED')),
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    bundle_sha256 TEXT,
    bundle_bytes BIGINT,
    manifest_sha256 TEXT,
    manifest_bytes BIGINT,
    columns INT,
    failure TEXT,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    projection_pending BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (state <> 'READY' OR (bundle_sha256 IS NOT NULL AND bundle_bytes IS NOT NULL AND manifest_sha256 IS NOT NULL AND manifest_bytes IS NOT NULL AND columns IS NOT NULL AND bundle_sha256 ~ '^[a-f0-9]{64}$' AND bundle_bytes BETWEEN 1 AND 134217728
        AND manifest_sha256 ~ '^[a-f0-9]{64}$' AND manifest_bytes BETWEEN 1 AND 1048576 AND columns BETWEEN 1 AND 4096)),
    CHECK (NOT published OR state = 'READY')
);
-- Defence in depth even if a future caller forgets the transaction's global admission lock.
CREATE UNIQUE INDEX map_lod_one_global_generator ON map_lod ((true)) WHERE state = 'GENERATING';
CREATE UNIQUE INDEX map_lod_one_live_identity ON map_lod (identity_sha256) WHERE state IN ('GENERATING', 'READY');
