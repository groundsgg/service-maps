-- Durable one-time automatic admission for an immutable input identity. No work or budgets
-- are seeded here, and historical reservations and failed attempts are never reset.
CREATE TABLE map_lod_auto_selection (
    identity_sha256 TEXT PRIMARY KEY CHECK (identity_sha256 ~ '^[a-f0-9]{64}$'),
    run_id UUID NOT NULL UNIQUE REFERENCES map_lod_run(id),
    budget_id TEXT NOT NULL REFERENCES map_lod_budget(id)
);
