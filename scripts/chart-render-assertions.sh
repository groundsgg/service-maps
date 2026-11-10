#!/usr/bin/env sh
set -eu

chart_dir=${1:-chart}
endpoint=https://acct.eu.r2.cloudflarestorage.com
digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
disabled=$(mktemp)
enabled=$(mktemp)
metrics=$(mktemp)
trap 'rm -f "$disabled" "$enabled" "$metrics"' EXIT

die() { echo "chart render assertion failed: $*" >&2; exit 1; }
expect() { grep -F -- "$1" "$2" >/dev/null || die "missing $1"; }
reject() {
  if grep -F -- "$1" "$2" >/dev/null; then
    die "unexpected $1"
  fi
}
must_fail() {
  if "$@" >/dev/null 2>&1; then
    die "expected command to fail: $*"
  fi
}
worker_sa_is_secure() {
  awk '
    /^---$/ { in_worker=0 }
    /name: service-maps-derive-worker$/ { in_worker=1 }
    in_worker && /automountServiceAccountToken: false/ { token_off=1 }
    in_worker && /secretKeyRef:|MAPS_R2_ACCESS_KEY_ID|MAPS_R2_SECRET_ACCESS_KEY/ { secret_ref=1 }
    END { exit !(token_off && !secret_ref) }
  ' "$1"
}
service_token_is_disabled() {
  awk '
    /serviceAccountName: service-maps$/ { in_service=1 }
    in_service && /automountServiceAccountToken: false/ { token_off=1 }
    END { exit !token_off }
  ' "$1"
}

helm template t "$chart_dir" --set image.tag=test --set config.r2.endpoint="$endpoint" >"$disabled"
service_token_is_disabled "$disabled" || die 'service account token automount is enabled'
reject 'name: service-maps-derive-worker' "$disabled"
reject 'name: kube-api-access' "$disabled"
reject 'name: MAPS_DERIVE_ENABLED' "$disabled"
reject 'kind: ServiceMonitor' "$disabled"

helm template t "$chart_dir" --namespace maps-monitor-test --set image.tag=test \
  --set config.r2.endpoint="$endpoint" --set monitoring.serviceMonitor.enabled=true >"$metrics"
expect 'kind: ServiceMonitor' "$metrics"
[ "$(grep -c '^kind: ServiceMonitor$' "$metrics")" -eq 1 ] || die 'expected exactly one ServiceMonitor'
awk '
  /^---$/ { in_monitor=0; in_selector=0; in_endpoints=0 }
  /^kind: ServiceMonitor$/ { in_monitor=1 }
  in_monitor && /namespace: maps-monitor-test$/ { namespace=1 }
  in_monitor && /^  selector:$/ { in_selector=1 }
  in_monitor && /^  endpoints:$/ { in_selector=0; in_endpoints=1 }
  in_selector && /app.kubernetes.io\/name: service-maps$/ { name=1 }
  in_selector && /app.kubernetes.io\/instance: t$/ { instance=1 }
  in_endpoints && /port: http$/ { port=1 }
  in_endpoints && /path: \/q\/metrics$/ { path=1 }
  in_endpoints && /interval: 30s$/ { interval=1 }
  END { exit !(namespace && name && instance && port && path && interval) }
' "$metrics" || die 'ServiceMonitor must select the release Service and scrape its named HTTP metrics endpoint'

helm template t "$chart_dir" --set image.tag=test --set config.r2.endpoint="$endpoint" \
  --set derive.enabled=true --set derive.worker.image="ghcr.io/groundsgg/service-maps@$digest" >"$enabled"
expect 'name: service-maps-derive-worker' "$enabled"
worker_sa_is_secure "$enabled" || die 'derive worker ServiceAccount is not token-disabled and secret-free'
expect 'name: kube-api-access' "$enabled"
expect 'mountPath: /var/run/secrets/kubernetes.io/serviceaccount' "$enabled"
expect 'expirationSeconds: 3600' "$enabled"
for env in MAPS_DERIVE_ENABLED MAPS_DERIVE_REQUIRED MAPS_DERIVE_NAMESPACE MAPS_DERIVE_IMAGE \
  MAPS_DERIVE_SERVICE_ACCOUNT MAPS_DERIVE_POLL_INTERVAL MAPS_DERIVE_RECONCILE_BATCH_SIZE \
  MAPS_DERIVE_URL_TTL MAPS_CATALOGS_BASE_URI MAPS_CATALOGS_PACK_SET MAPS_CATALOGS_CACHE_ROOT; do
  expect "name: $env" "$enabled"
done

must_fail helm template t "$chart_dir" --set image.tag=test --set config.r2.endpoint="$endpoint" --set derive.required=true
must_fail helm template t "$chart_dir" --set image.tag=test --set config.r2.endpoint="$endpoint" --set derive.enabled=true
must_fail helm template t "$chart_dir" --set image.tag=test --set config.r2.endpoint="$endpoint" --set derive.enabled=true --set derive.worker.image=ghcr.io/groundsgg/service-maps:latest

# LOD admission is independent and off by default; it requires a concrete existing node and run.
expect 'name: MAPS_LOD_ENABLED' "$disabled"
expect 'name: MAPS_LOD_PUBLICATION_ENABLED' "$disabled"
must_fail helm template t "$chart_dir" --set config.r2.endpoint="$endpoint" --set lod.enabled=true
must_fail helm template t "$chart_dir" --set config.r2.endpoint="$endpoint" --set derive.enabled=true \
  --set derive.worker.image="ghcr.io/groundsgg/service-maps@$digest" --set lod.enabled=true
must_fail helm template t "$chart_dir" --set config.r2.endpoint="$endpoint" --set derive.enabled=true \
  --set derive.worker.image="ghcr.io/groundsgg/service-maps@$digest" --set lod.enabled=true --set lod.node=existing-node --set lod.runId=invalid
helm template t "$chart_dir" --set config.r2.endpoint="$endpoint" --set derive.enabled=true \
  --set derive.worker.image="ghcr.io/groundsgg/service-maps@$digest" --set lod.enabled=true --set lod.node=existing-node \
  --set lod.runId=12345678-1234-1234-1234-123456789abc >"$enabled"
expect 'name: MAPS_LOD_RUN_ID' "$enabled"
expect 'value: "existing-node"' "$enabled"
awk '/name: MAPS_LOD_PUBLICATION_ENABLED/ { getline; if ($0 !~ /value: "false"/) exit 1; found=1 } END { if (!found) exit 1 }' "$enabled" || die 'LOD artifacts must remain private by default'

# Ordinary Stage serving is a distinct private mode; automatic generation needs no manual run UUID.
helm template t "$chart_dir" --set config.r2.endpoint="$endpoint" --set derive.enabled=true \
  --set derive.worker.image="ghcr.io/groundsgg/service-maps@$digest" \
  --set lod.enabled=true --set lod.node=existing-node --set lod.auto.enabled=true \
  --set lod.auto.environment=stage --set lod.auto.namespace=lobby --set lod.auto.name=mainlobby \
  --set lod.auto.budgetId=stage-lobby-auto-v1 \
  --set lod.auto.registrySha256="$(printf '%064d' 0)" \
  --set lod.auto.generatorSha256="$(printf '%064d' 1)" \
  --set lod.auto.workerImage="ghcr.io/groundsgg/service-maps@$digest" \
  --set lod.serving.enabled=true --set lod.serving.budgetId=stage-lobby-serving-v1 \
  --set lod.serving.generationBudgetId=stage-lobby-auto-v1 >"$enabled"
for env in MAPS_LOD_AUTO_ENABLED MAPS_LOD_AUTO_ENVIRONMENT MAPS_LOD_AUTO_NAMESPACE MAPS_LOD_AUTO_NAME \
  MAPS_LOD_AUTO_BUDGET_ID MAPS_LOD_AUTO_REGISTRY_SHA256 MAPS_LOD_AUTO_GENERATOR_SHA256 \
  MAPS_LOD_AUTO_WORKER_IMAGE MAPS_LOD_SERVING_ENABLED MAPS_LOD_SERVING_BUDGET_ID MAPS_LOD_SERVING_GENERATION_BUDGET_ID; do
  expect "name: $env" "$enabled"
done
awk '/name: MAPS_LOD_AUTO_ENABLED/ { getline; if ($0 !~ /value: "true"/) exit 1; found=1 } END { if (!found) exit 1 }' "$enabled" || die 'explicit automatic LOD mode missing'
awk '/name: MAPS_LOD_SERVING_ENABLED/ { getline; if ($0 !~ /value: "false"/) exit 1; found=1 } END { if (!found) exit 1 }' "$disabled" || die 'ordinary serving must default off'
must_fail helm template t "$chart_dir" --set config.r2.endpoint="$endpoint" --set lod.auto.enabled=true
must_fail helm template t "$chart_dir" --set config.r2.endpoint="$endpoint" --set lod.serving.enabled=true
must_fail helm template t "$chart_dir" --set config.r2.endpoint="$endpoint" --set lod.auto.enabled=true --set lod.auto.environment=prod
must_fail helm template t "$chart_dir" --set config.r2.endpoint="$endpoint" --set lod.serving.enabled=true --set lod.publication.enabled=true
