#!/usr/bin/env sh
set -eu

chart_dir=${1:-chart}
endpoint=https://acct.eu.r2.cloudflarestorage.com
digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
disabled=$(mktemp)
enabled=$(mktemp)
trap 'rm -f "$disabled" "$enabled"' EXIT

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
