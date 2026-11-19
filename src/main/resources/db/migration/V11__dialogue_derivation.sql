-- Old rows remain uninspected. Defaults only affect newly inserted versions.
ALTER TABLE map_version
    ADD COLUMN dialogue_projection JSONB,
    ADD COLUMN derive_execution_contract JSONB,
    ADD COLUMN derive_contract_legacy BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE map_version ALTER COLUMN derive_contract_legacy SET DEFAULT FALSE;
ALTER TABLE map_version ALTER COLUMN dialogue_projection SET DEFAULT '{"status":"UNKNOWN","problems":[]}'::jsonb;
ALTER TABLE map_version ADD CONSTRAINT dialogue_projection_shape CHECK (
    dialogue_projection IS NULL OR (
        jsonb_typeof(dialogue_projection) = 'object' AND
        jsonb_typeof(dialogue_projection -> 'status') = 'string' AND
        dialogue_projection ->> 'status' IN ('UNKNOWN','PENDING','NONE','VALID','INVALID')
    )
);
ALTER TABLE map_version ADD CONSTRAINT derive_execution_contract_shape CHECK (
    derive_execution_contract IS NULL OR (
        jsonb_typeof(derive_execution_contract) = 'object' AND
        jsonb_typeof(derive_execution_contract -> 'schemaVersion') = 'number' AND
        derive_execution_contract ->> 'schemaVersion' IN ('1','2') AND
        jsonb_typeof(derive_execution_contract -> 'workerImage') = 'string' AND
        derive_execution_contract ->> 'workerImage' ~ '^[^[:space:]]+@sha256:[0-9a-f]{64}$' AND
        jsonb_typeof(derive_execution_contract -> 'dialogueProfiles') = 'array'
    )
);
ALTER TABLE map_version_derive_problem
    ADD COLUMN component TEXT,
    ADD COLUMN severity TEXT,
    ADD COLUMN file_name TEXT,
    ADD COLUMN pointer TEXT,
    ADD COLUMN node_id TEXT,
    ADD CONSTRAINT derive_problem_severity CHECK (severity IS NULL OR severity IN ('ERROR','WARNING'));
