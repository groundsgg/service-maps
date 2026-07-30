-- The map registry. Bytes live in R2 keyed by content hash; this schema is the
-- only place that says which hash means which version of which map, and which
-- version players currently get.
--
-- Two invariants shape every table here:
--   1. Identity is the content hash. No row ever names a mutable object.
--   2. A version is never reused, never overwritten and never deleted, so
--      rollback is a pointer move rather than a rebuild.

-- A map is addressed as "<namespace>/<name>", e.g. bedwars/4x4-baumhaus.
-- The namespace is normally a gamemode, `grounds` for platform-owned maps, or
-- `u/<creator>` for creator content — which is why it may itself contain one
-- slash and callers must not assume a two-segment address.
--
-- The namespace does four jobs at once: it names the config schema the derive
-- step validates against, it is the permission scope, it is the quota bucket,
-- and it drives mode-to-map resolution.
CREATE TABLE IF NOT EXISTS map (
    id                   UUID         PRIMARY KEY,
    namespace            TEXT         NOT NULL,
    name                 TEXT         NOT NULL,
    display_name         TEXT         NOT NULL,
    kind                 TEXT         NOT NULL,
    -- Stateful maps are written back by exactly one lease-holding process. It is
    -- a property of the map, not a per-load flag, because it changes the runtime
    -- shape: a stateful world is loaded writable and is never cloned per match.
    stateful             BOOLEAN      NOT NULL DEFAULT FALSE,
    trust                TEXT         NOT NULL DEFAULT 'FIRST_PARTY',
    owner_sub            TEXT         NOT NULL,
    -- Provenance of a fork, not a live link: the parent moving on changes
    -- nothing here, and there is deliberately no merge operation.
    forked_from_map      UUID         REFERENCES map (id),
    forked_from_version  INTEGER,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT map_address_unique UNIQUE (namespace, name),
    -- Either both fork columns are set or neither is; a half-recorded ancestry
    -- would read as "forked from version 3 of nothing".
    CONSTRAINT map_fork_origin_complete CHECK (
        (forked_from_map IS NULL) = (forked_from_version IS NULL)
    )
);

-- No index on (namespace, name): map_address_unique already creates exactly that btree,
-- and a second copy would be written on every insert and read by nothing.
CREATE INDEX IF NOT EXISTS map_forked_from_idx ON map (forked_from_map)
    WHERE forked_from_map IS NOT NULL;

-- Monotonic integers per map, allocated on commit. The hashes stay NULL until
-- the derive Job has assembled the bundle, which is why the state column and not
-- the presence of a hash is what says whether a version is usable.
CREATE TABLE IF NOT EXISTS map_version (
    -- RESTRICT, not CASCADE: this file opens by saying a version is never deleted, and a
    -- cascade would make `DELETE FROM map` a way to delete history without ever naming it.
    -- A map with versions cannot be dropped; retiring one is a state, not a DELETE.
    map              UUID         NOT NULL REFERENCES map (id) ON DELETE RESTRICT,
    version          INTEGER      NOT NULL,
    state            TEXT         NOT NULL,
    bundle_sha256    TEXT,
    source_sha256    TEXT,
    -- Where the uploaded source still sits in the private bucket, so the derive Job can
    -- find it. Null on a fork, which has no upload of its own.
    source_key       TEXT,
    manifest_sha256  TEXT,
    parent_version   INTEGER,
    size_bytes       BIGINT,
    present_chunks   INTEGER,
    -- Measured by the derive Job on a pod-shaped JVM, not estimated from file
    -- size: memory, not bandwidth, is what stops a map from being publishable.
    est_loaded_mib   INTEGER,
    published_by_sub TEXT         NOT NULL,
    note             TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (map, version),
    CONSTRAINT map_version_positive CHECK (version > 0),
    -- Provenance has to point at something. NULL is allowed (a first version descends from
    -- nothing); a number that names no version is not.
    CONSTRAINT map_version_parent_exists
        FOREIGN KEY (map, parent_version) REFERENCES map_version (map, version),
    CONSTRAINT map_version_parent_is_older CHECK (parent_version IS NULL OR parent_version < version)
);

CREATE INDEX IF NOT EXISTS map_version_bundle_idx ON map_version (bundle_sha256)
    WHERE bundle_sha256 IS NOT NULL;

-- Every blob the registry has ever seen, and whether it has been promoted into
-- the public bucket. Refcounts are deliberately NOT stored: orphan collection
-- derives them by diffing the bucket against what manifests actually reference,
-- because a stored counter that drifts deletes live data.
CREATE TABLE IF NOT EXISTS map_blob (
    sha256         TEXT         PRIMARY KEY,
    size_bytes     BIGINT       NOT NULL,
    public         BOOLEAN      NOT NULL DEFAULT FALSE,
    first_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- What players get, one version per map per environment. This table is the
-- source; `pins/<env>.json` on the CDN is its published projection, and that
-- file is what running servers actually read.
CREATE TABLE IF NOT EXISTS map_pin (
    environment   TEXT         NOT NULL,
    map           UUID         NOT NULL REFERENCES map (id) ON DELETE CASCADE,
    version       INTEGER      NOT NULL,
    moved_by_sub  TEXT         NOT NULL,
    moved_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (environment, map),
    FOREIGN KEY (map, version) REFERENCES map_version (map, version)
);

-- Append-only, so "put version 7 back in front of players" is auditable and so
-- undoing an undo is the same operation as the undo.
CREATE TABLE IF NOT EXISTS map_pin_history (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    environment   TEXT         NOT NULL,
    map           UUID         NOT NULL,
    from_version  INTEGER,
    to_version    INTEGER      NOT NULL,
    moved_by_sub  TEXT         NOT NULL,
    moved_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
