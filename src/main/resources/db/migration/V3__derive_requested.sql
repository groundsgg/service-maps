-- A source-backed version enters asynchronous derivation only after its caller explicitly opts
-- in. Existing rows are legacy compatibility versions, so their immutable default is false.
ALTER TABLE map_version
    ADD COLUMN derive_requested BOOLEAN NOT NULL DEFAULT FALSE;
