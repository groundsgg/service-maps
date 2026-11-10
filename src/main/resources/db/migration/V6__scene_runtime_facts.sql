ALTER TABLE map_version ADD COLUMN scene_runtime_facts JSONB;
ALTER TABLE map_version ADD CONSTRAINT map_version_runtime_facts_object CHECK (scene_runtime_facts IS NULL OR jsonb_typeof(scene_runtime_facts) = 'object');
