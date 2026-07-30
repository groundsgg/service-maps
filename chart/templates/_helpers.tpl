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
