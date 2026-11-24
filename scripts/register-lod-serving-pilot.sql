-- Registers one finite serving pilot, never resets counters or activates a deployment flag.
-- Supply: run_id UUID, artifact_attempt UUID, token_sha256 lowercase SHA256,
-- players a PostgreSQL UUID array literal, e.g. '{uuid1,uuid2}'. Never supply the token itself.
\set ON_ERROR_STOP on
\if :{?run_id}
\else
\quit 1
\endif
\if :{?artifact_attempt}
\else
\quit 1
\endif
\if :{?token_sha256}
\else
\quit 1
\endif
\if :{?players}
\else
\quit 1
\endif
BEGIN;
SET LOCAL search_path=maps;
WITH selected AS (
    INSERT INTO map_lod_serving_run(id,artifact_attempt,token_sha256,allowed_players,
        starts_at,expires_at,created_by)
    SELECT :'run_id'::uuid,a.attempt,:'token_sha256',:'players'::uuid[],
        now(),now()+interval '30 minutes',current_user
      FROM map_lod a
      JOIN map_lod_run r ON r.id=a.run_id
     WHERE a.attempt=:'artifact_attempt'::uuid AND a.state='READY' AND a.trust='FIRST_PARTY'
       AND r.environment='stage' AND r.namespace='lobby' AND r.name='mainlobby' AND r.version=13
       AND a.map_bundle_sha256='a2aa86931891165e39111461f7f664264260379ce184a4f2b1a8f4f158456f84'
       AND a.source_bundle_sha256='8c0303b517afec90d31dbb581bd627cdb3a9a348770eae8bb24af7d1a3b37c92'
    RETURNING id
)
SELECT count(*) AS registered_lod_serving_run FROM selected
\gset
\if :registered_lod_serving_run
COMMIT;
\echo 'Registered a 30-minute private Lobby pilot. Deployment flag remains unchanged.'
\else
ROLLBACK;
\echo 'LOD serving selection refused: expected a verified READY artifact of stage Lobby v13.'
\quit 1
\endif
