-- Operator-created, fixed measurement envelope. No insert/reset on boot, pin change, or discovery.
CREATE TABLE map_lod_delivery_budget (
    id TEXT PRIMARY KEY CHECK (id ~ '^[a-z0-9-]{1,64}$'),
    token_sha256 TEXT NOT NULL CHECK (token_sha256 ~ '^[a-f0-9]{64}$'),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    payload_grants_reserved INT NOT NULL DEFAULT 0 CHECK (payload_grants_reserved BETWEEN 0 AND 8192),
    payload_reserved BIGINT NOT NULL DEFAULT 0 CHECK (payload_reserved BETWEEN 0 AND 1073741824),
    remote_reads_reserved INT NOT NULL DEFAULT 0 CHECK (remote_reads_reserved BETWEEN 0 AND 10000),
    remote_bytes_reserved BIGINT NOT NULL DEFAULT 0 CHECK (remote_bytes_reserved BETWEEN 0 AND 1073741824)
);
ALTER TABLE map_lod_serving_run ADD COLUMN mode TEXT NOT NULL DEFAULT 'PILOT' CHECK (mode IN ('PILOT','ENTITLED'));
ALTER TABLE map_lod_serving_run ADD COLUMN budget_id TEXT REFERENCES map_lod_delivery_budget(id);
ALTER TABLE map_lod_serving_run ADD COLUMN generation_budget_id TEXT REFERENCES map_lod_budget(id);
ALTER TABLE map_lod_serving_run ALTER COLUMN expires_at DROP NOT NULL;
ALTER TABLE map_lod_serving_run DROP CONSTRAINT map_lod_serving_run_allowed_players_check;
ALTER TABLE map_lod_serving_run DROP CONSTRAINT map_lod_serving_run_check;
ALTER TABLE map_lod_serving_run ADD CONSTRAINT map_lod_serving_mode_contract CHECK (
    (mode='PILOT' AND budget_id IS NULL AND generation_budget_id IS NULL
        AND cardinality(allowed_players) BETWEEN 1 AND 5 AND expires_at IS NOT NULL
        AND expires_at > starts_at AND expires_at <= starts_at + interval '30 minutes')
    OR (mode='ENTITLED' AND budget_id IS NOT NULL AND generation_budget_id IS NOT NULL
        AND cardinality(allowed_players)=0 AND expires_at IS NULL)
);
DROP INDEX map_lod_one_enabled_serving_run;
CREATE UNIQUE INDEX map_lod_one_enabled_serving_run ON map_lod_serving_run ((true)) WHERE enabled AND mode='PILOT';
CREATE UNIQUE INDEX map_lod_entitled_selection ON map_lod_serving_run (budget_id,generation_budget_id,artifact_attempt) WHERE mode='ENTITLED';
-- Both quotas are shared across immutable map selections and service replicas.
CREATE TABLE map_lod_delivery_window (
    budget_id TEXT NOT NULL REFERENCES map_lod_delivery_budget(id),
    player UUID NOT NULL,
    window_second BIGINT NOT NULL,
    bytes_reserved INT NOT NULL CHECK (bytes_reserved BETWEEN 1 AND 524288),
    PRIMARY KEY (budget_id,player,window_second)
);
CREATE TABLE map_lod_delivery_global_window (
    budget_id TEXT NOT NULL REFERENCES map_lod_delivery_budget(id),
    window_second BIGINT NOT NULL,
    bytes_reserved INT NOT NULL CHECK (bytes_reserved BETWEEN 1 AND 4194304),
    PRIMARY KEY (budget_id,window_second)
);
