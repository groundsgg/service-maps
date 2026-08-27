ALTER TABLE map_version
    ADD COLUMN scene_present BOOLEAN,
    ADD COLUMN scene_schema_version INTEGER,
    ADD COLUMN scene_sha256 TEXT,
    ADD COLUMN asset_catalog_id TEXT,
    ADD COLUMN asset_catalog_version TEXT,
    ADD COLUMN action_catalog_id TEXT,
    ADD COLUMN action_catalog_version TEXT,
    ADD COLUMN derive_attempt UUID,
    ADD COLUMN derive_failure_scope TEXT,
    ADD COLUMN derive_retryable BOOLEAN NOT NULL DEFAULT FALSE;

-- Versions published before scene derivation existed are successful no-scene versions. Drafts and
-- other unfinished historical rows stay unknown until a derivation attempt inspects them.
UPDATE map_version SET scene_present = FALSE WHERE state = 'PUBLISHED';

ALTER TABLE map_version
    ADD CONSTRAINT map_version_scene_sha256_valid CHECK (
        scene_sha256 IS NULL OR scene_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT map_version_derive_failure_scope_valid CHECK (
        derive_failure_scope IS NULL OR derive_failure_scope IN ('CONTENT', 'SYSTEM')
    ),
    ADD CONSTRAINT map_version_asset_catalog_complete CHECK (
        (asset_catalog_id IS NULL) = (asset_catalog_version IS NULL)
    ),
    ADD CONSTRAINT map_version_action_catalog_complete CHECK (
        (action_catalog_id IS NULL) = (action_catalog_version IS NULL)
    ),
    ADD CONSTRAINT map_version_published_scene_complete CHECK (
        state <> 'PUBLISHED' OR CASE
            WHEN scene_present = FALSE THEN
                scene_schema_version IS NULL AND scene_sha256 IS NULL AND
                asset_catalog_id IS NULL AND asset_catalog_version IS NULL AND
                action_catalog_id IS NULL AND action_catalog_version IS NULL
            WHEN scene_present = TRUE THEN
                scene_schema_version IS NOT NULL AND scene_sha256 IS NOT NULL AND
                asset_catalog_id IS NOT NULL AND asset_catalog_version IS NOT NULL AND
                action_catalog_id IS NOT NULL AND action_catalog_version IS NOT NULL
            ELSE FALSE
        END
    );

CREATE TABLE map_version_required_action (
    map       UUID    NOT NULL,
    version   INTEGER NOT NULL,
    action_id TEXT    NOT NULL,
    PRIMARY KEY (map, version, action_id),
    FOREIGN KEY (map, version) REFERENCES map_version (map, version) ON DELETE RESTRICT
);

CREATE TABLE map_version_derive_problem (
    map                UUID    NOT NULL,
    version            INTEGER NOT NULL,
    ordinal            INTEGER NOT NULL,
    scope              TEXT    NOT NULL,
    path               TEXT,
    code               TEXT    NOT NULL,
    qualified_identity TEXT,
    message            TEXT    NOT NULL,
    PRIMARY KEY (map, version, ordinal),
    FOREIGN KEY (map, version) REFERENCES map_version (map, version) ON DELETE RESTRICT,
    CONSTRAINT map_version_derive_problem_ordinal_non_negative CHECK (ordinal >= 0),
    CONSTRAINT map_version_derive_problem_scope_valid CHECK (scope IN ('CONTENT', 'SYSTEM'))
);
