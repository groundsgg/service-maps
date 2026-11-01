-- A lit copy of a published bundle: the same world with its light computed once, so game servers
-- never light a map themselves. Keyed by the bundle, not the version: a fork shares its parent's
-- bundle and so its lit copy, and no version or pin changes when a lit copy appears. The light
-- format is the version of the game servers' lighting contract; raising it lights every bundle
-- again without touching the copies older servers still read.
CREATE TABLE map_lit (
    bundle_sha256 TEXT        NOT NULL,
    light_format  INT         NOT NULL,
    state         TEXT        NOT NULL CHECK (state IN ('LIGHTING', 'LIT', 'FAILED')),
    attempt       UUID        NOT NULL,
    attempts      INT         NOT NULL DEFAULT 1,
    lit_sha256    TEXT,
    lit_size      BIGINT,
    kept_chunks   INT,
    failure       TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bundle_sha256, light_format),
    CHECK (state <> 'LIT' OR (lit_sha256 IS NOT NULL AND lit_size IS NOT NULL))
);

CREATE INDEX map_lit_state_idx ON map_lit (light_format, state);
