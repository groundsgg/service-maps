-- Explicit operator registration only. This does not enable the reconciler or move a map pin.
-- psql -v run_id=<uuid> -v worker_image=<repository@sha256:digest> \
--      -v generator_sha256=<from worker --identity> -v registry_sha256=<from worker --identity> \
--      -f scripts/register-lod-pilot.sql <normal database connection arguments>
\set ON_ERROR_STOP on
\if :{?run_id}
\else
\echo 'run_id is required'
\quit 1
\endif
\if :{?worker_image}
\else
\echo 'worker_image is required'
\quit 1
\endif
\if :{?generator_sha256}
\else
\echo 'generator_sha256 is required'
\quit 1
\endif
\if :{?registry_sha256}
\else
\echo 'registry_sha256 is required'
\quit 1
\endif
BEGIN;
SET LOCAL search_path=maps;
-- Never reset existing counters, including after a failed run or service restart.
INSERT INTO map_lod_budget(id) VALUES ('lobby-v13-lss20-v1') ON CONFLICT DO NOTHING;
WITH registered AS (
    INSERT INTO map_lod_run(id,budget_id,environment,namespace,name,version,map_bundle_sha256,
        source_bundle_sha256,registry_sha256,generator_sha256,worker_image,created_at,expires_at,created_by)
    SELECT :'run_id'::uuid,'lobby-v13-lss20-v1',p.environment,m.namespace,m.name,v.version,
        v.bundle_sha256,l.lit_sha256,:'registry_sha256',:'generator_sha256',:'worker_image',
        now(),now()+interval '30 minutes',current_user
      FROM map m
      JOIN map_pin p ON p.map=m.id AND p.environment='stage' AND p.version=13
      JOIN map_version v ON v.map=m.id AND v.version=13 AND v.state='PUBLISHED'
      JOIN map_lit l ON l.bundle_sha256=v.bundle_sha256 AND l.light_format=1 AND l.state='LIT'
     WHERE m.namespace='lobby' AND m.name='mainlobby' AND m.trust='FIRST_PARTY'
       AND v.bundle_sha256='a2aa86931891165e39111461f7f664264260379ce184a4f2b1a8f4f158456f84'
       AND l.lit_sha256='8c0303b517afec90d31dbb581bd627cdb3a9a348770eae8bb24af7d1a3b37c92'
       AND :'worker_image' ~ '^[^[:space:]]+@sha256:[a-f0-9]{64}$'
    RETURNING id
)
SELECT count(*) AS registered_lod_runs FROM registered
\gset
\if :registered_lod_runs
COMMIT;
\echo 'Registered the exact Lobby v13 selection. Admission remains controlled by lod.enabled and lod.runId.'
\else
ROLLBACK;
\echo 'LOD selection refused: the stage pin, canonical input, prelit input, trust or immutable image does not match.'
\quit 1
\endif
