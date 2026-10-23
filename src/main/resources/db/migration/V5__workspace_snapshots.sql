-- A buildserver's working copy: the files that make up its worlds and plugin state, so a pod
-- that loses its disk can be put back exactly. This is a backup of a *server*, not a map
-- version: nothing here is pinnable, derived or visible in a map's history.
--
-- See docs/superpowers/specs/2026-10-23-buildserver-working-copy-design.md.
CREATE TABLE IF NOT EXISTS workspace_snapshot (
    server        TEXT         NOT NULL,
    snapshot      BIGINT       NOT NULL,
    state         TEXT         NOT NULL,
    -- The head this snapshot was built on. Commit refuses unless it is still the head, which is
    -- what turns two writers into a conflict instead of a silent interleaving.
    base          BIGINT,
    -- [{path, sha256, size}], sorted by path. Always read and written whole.
    manifest      JSONB        NOT NULL,
    -- [{sha256, size}] the client was told to upload; commit checks exactly these exist.
    pending       JSONB        NOT NULL,
    file_count    INTEGER      NOT NULL,
    total_bytes   BIGINT       NOT NULL,
    created_by    TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    committed_at  TIMESTAMPTZ,
    PRIMARY KEY (server, snapshot),
    CONSTRAINT workspace_snapshot_state_valid CHECK (state IN ('OPEN', 'COMMITTED')),
    CONSTRAINT workspace_snapshot_committed_at CHECK ((state = 'COMMITTED') = (committed_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS workspace_head (
    server    TEXT    PRIMARY KEY,
    snapshot  BIGINT  NOT NULL,
    FOREIGN KEY (server, snapshot) REFERENCES workspace_snapshot (server, snapshot)
);

-- Blobs known to be in the bucket, recorded only when a commit has verified them. This is what
-- keeps a sync cheap: "missing" is a lookup here, not a HEAD per file every minute.
CREATE TABLE IF NOT EXISTS workspace_blob (
    server      TEXT    NOT NULL,
    sha256      TEXT    NOT NULL,
    size_bytes  BIGINT  NOT NULL,
    PRIMARY KEY (server, sha256),
    CONSTRAINT workspace_blob_sha256_valid CHECK (sha256 ~ '^[0-9a-f]{64}$')
);
