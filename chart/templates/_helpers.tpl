{{- define "service-maps.name" -}}service-maps{{- end -}}

{{- define "service-maps.labels" -}}
app.kubernetes.io/name: {{ include "service-maps.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{- define "service-maps.selectorLabels" -}}
app.kubernetes.io/name: {{ include "service-maps.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "service-maps.validateDerive" -}}
{{- if .Values.lod.enabled -}}
{{- if not .Values.derive.enabled -}}{{- fail "lod.enabled requires derive.enabled for scoped Kubernetes access" -}}{{- end -}}
{{- $node := required "lod.node must name an existing worker hostname" .Values.lod.node -}}
{{- if not .Values.lod.auto.enabled -}}
{{- $run := required "lod.runId must name an explicit persistent run" .Values.lod.runId -}}
{{- if not (regexMatch "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$" $run) -}}
{{- fail "lod.runId must be a lowercase UUID" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- if and .Values.lod.auto.enabled (not .Values.lod.enabled) -}}
{{- fail "lod.auto.enabled requires lod.enabled" -}}
{{- end -}}
{{- if or .Values.lod.auto.enabled .Values.lod.serving.enabled -}}
{{- if .Values.lod.publication.enabled -}}{{- fail "automatic entitled LOD artifacts must remain private" -}}{{- end -}}
{{- end -}}
{{- if .Values.lod.auto.enabled -}}
{{- if not (and (eq .Values.lod.auto.environment "stage") (eq .Values.lod.auto.namespace "lobby") (eq .Values.lod.auto.name "mainlobby")) -}}
{{- fail "automatic LOD selection must be Stage lobby/mainlobby" -}}
{{- end -}}
{{- if not (regexMatch "^[a-z0-9-]{1,64}$" .Values.lod.auto.budgetId) -}}{{- fail "automatic LOD requires a stable budget id" -}}{{- end -}}
{{- range $identity := list .Values.lod.auto.registrySha256 .Values.lod.auto.generatorSha256 -}}
{{- if not (regexMatch "^[a-f0-9]{64}$" $identity) -}}{{- fail "automatic LOD requires registry and generator SHA256" -}}{{- end -}}
{{- end -}}
{{- if not (regexMatch "^[^@[:space:]]+@sha256:[a-f0-9]{64}$" .Values.lod.auto.workerImage) -}}{{- fail "automatic LOD requires an immutable worker image" -}}{{- end -}}
{{- end -}}
{{- if .Values.lod.serving.enabled -}}
{{- range $budget := list .Values.lod.serving.budgetId .Values.lod.serving.generationBudgetId -}}
{{- if not (regexMatch "^[a-z0-9-]{1,64}$" $budget) -}}{{- fail "entitled LOD serving requires stable budget ids" -}}{{- end -}}
{{- end -}}
{{- if and .Values.lod.auto.enabled (ne .Values.lod.serving.generationBudgetId .Values.lod.auto.budgetId) -}}{{- fail "entitled LOD generation budget does not match automatic admission" -}}{{- end -}}
{{- end -}}

{{- if and .Values.derive.required (not .Values.derive.enabled) -}}
{{- fail "derive.required=true requires derive.enabled=true" -}}
{{- end -}}
{{- if .Values.derive.enabled -}}
{{- $image := required "derive.worker.image is required when derive.enabled=true" .Values.derive.worker.image -}}
{{- if not (regexMatch "^.+@sha256:[a-f0-9]{64}$" $image) -}}
{{- fail "derive.worker.image must be an immutable repository@sha256:<64 lowercase hex> reference" -}}
{{- end -}}
{{- end -}}
{{- end -}}
