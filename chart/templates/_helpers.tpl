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
