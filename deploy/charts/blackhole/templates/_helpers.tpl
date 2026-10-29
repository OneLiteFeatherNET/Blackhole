{{/*
Helpers of the Blackhole chart. Most take a dict: {ctx: <root context>, component: <name>, values: <component values>}.
*/}}

{{/* Release-scoped name of a component: <release>-<component>, e.g. blackhole-backend. dict: ctx, component */}}
{{- define "blackhole.fullname" -}}
{{- printf "%s-%s" .ctx.Release.Name .component | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Selector labels. dict: ctx, component */}}
{{- define "blackhole.selectorLabels" -}}
app.kubernetes.io/name: blackhole
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* Labels of a resource. dict: ctx, component */}}
{{- define "blackhole.labels" -}}
{{ include "blackhole.selectorLabels" . }}
app.kubernetes.io/part-of: blackhole
app.kubernetes.io/version: {{ .ctx.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .ctx.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .ctx.Chart.Name .ctx.Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end -}}

{{/*
Labels of a pod: the resource labels, the global and component pod labels and the two labels Alloy reads
(logs.onelitefeather.net/format: json makes it parse the stream as JSON; /env separates prod from dev).
dict: ctx, component, values
*/}}
{{- define "blackhole.podLabels" -}}
{{ include "blackhole.labels" . }}
{{- $extra := merge (dict)
      (.values.podLabels | default dict)
      (.ctx.Values.global.podLabels | default dict)
      (dict "logs.onelitefeather.net/format" (.values.logFormat | default "plain") "logs.onelitefeather.net/env" .ctx.Values.global.environment) }}
{{ toYaml $extra }}
{{- end -}}

{{/*
Annotations of a pod. secretRevision is how an operator rolls the pods after a Secret changed (the cluster
has no reloader and its Secret names are stable). dict: values, [extra annotations as `extra`]
*/}}
{{- define "blackhole.podAnnotations" -}}
{{- $a := merge (dict) (.values.podAnnotations | default dict) (.extra | default dict) -}}
{{- $_ := set $a "blackhole.onelitefeather.net/secret-revision" (.values.secretRevision | toString) -}}
{{ toYaml $a }}
{{- end -}}

{{/*
Image reference. dict: ctx, image, app (true for the Blackhole images, which fall back to the global tag
and then to the chart's appVersion; third-party images must carry their own tag).
*/}}
{{- define "blackhole.image" -}}
{{- $registry := .image.registry | default .ctx.Values.global.image.registry -}}
{{- $tag := .image.tag | toString -}}
{{- if and (not $tag) .app -}}
{{- $tag = .ctx.Values.global.image.tag | default .ctx.Chart.AppVersion | toString -}}
{{- end -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" $registry .image.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" .image.repository $tag -}}
{{- end -}}
{{- end -}}

{{/* A scalar as a string. Helm hands numbers over as float64, which would print 94371840 as 9.437184e+07. */}}
{{- define "blackhole.str" -}}
{{- if kindIs "float64" . -}}
{{- if eq (floor .) . -}}{{ int64 . }}{{- else -}}{{ . }}{{- end -}}
{{- else -}}
{{- . | toString -}}
{{- end -}}
{{- end -}}

{{/*
Container env from a map: scalars become `value`, an object becomes the entry itself (valueFrom: ...), null
removes the key. Sorted by name, so the output is stable.
*/}}
{{- define "blackhole.env" -}}
{{- range $name, $value := . }}
{{- if kindIs "map" $value }}
- name: {{ $name }}
  {{- toYaml $value | nindent 2 }}
{{- else if not (kindIs "invalid" $value) }}
- name: {{ $name }}
  value: {{ include "blackhole.str" $value | quote }}
{{- end }}
{{- end }}
{{- end -}}

{{/* envFrom from a map {<name>: Secret|ConfigMap}. */}}
{{- define "blackhole.envFrom" -}}
{{- range $name, $kind := . }}
{{- if eq (toString $kind) "ConfigMap" }}
- configMapRef:
    name: {{ $name }}
{{- else if eq (toString $kind) "Secret" }}
- secretRef:
    name: {{ $name }}
{{- end }}
{{- end }}
{{- end -}}

{{/* A secretKeyRef env entry. dict: name (env name), secret, key */}}
{{- define "blackhole.secretEnv" -}}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .secret }}
      key: {{ .key }}
{{- end -}}

{{/* Scheduling fields of a pod. dict: ctx, component, values, [spread: true] */}}
{{- define "blackhole.scheduling" -}}
{{- with .values.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .values.tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .values.affinity }}
affinity:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- if .values.topologySpreadConstraints }}
topologySpreadConstraints:
  {{- toYaml .values.topologySpreadConstraints | nindent 2 }}
{{- else if and .spread (gt (int .values.replicaCount) 1) }}
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway
    labelSelector:
      matchLabels:
        {{- include "blackhole.selectorLabels" . | nindent 8 }}
{{- end }}
{{- end -}}

{{/* Settings the pods of every component share. dict: ctx */}}
{{- define "blackhole.podDefaults" -}}
automountServiceAccountToken: false
enableServiceLinks: false
{{- with .Values.global.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.global.priorityClassName }}
priorityClassName: {{ . }}
{{- end }}
{{- end -}}

{{/* The URL of the backend inside the release unless a component sets its own. dict: ctx, url */}}
{{- define "blackhole.backendUrl" -}}
{{- .url | default (printf "http://%s:%d" (include "blackhole.fullname" (dict "ctx" .ctx "component" "backend")) (int .ctx.Values.backend.service.port)) -}}
{{- end -}}

{{/*
NATS credentials of a component: its own <component>.nats.existingSecret / credsKey, else the shared nats.*
(the backend and the bot are different NATS users). dict: ctx, values
*/}}
{{- define "blackhole.natsSecret" -}}
{{- (.values.nats | default dict).existingSecret | default .ctx.Values.nats.existingSecret -}}
{{- end -}}

{{- define "blackhole.natsCredsKey" -}}
{{- (.values.nats | default dict).credsKey | default .ctx.Values.nats.credsKey -}}
{{- end -}}

{{/* Path of the credentials file inside the pod. dict: ctx, values */}}
{{- define "blackhole.natsCredsFile" -}}
{{- printf "%s/%s" (.ctx.Values.nats.credsMountPath | trimSuffix "/") (include "blackhole.natsCredsKey" .) -}}
{{- end -}}

{{/* The Entra issuer and JWKS URL, derived from the tenant unless set. */}}
{{- define "blackhole.issuer" -}}
{{- .Values.auth.issuer | default (printf "https://login.microsoftonline.com/%s/v2.0" .Values.auth.tenantId) -}}
{{- end -}}

{{- define "blackhole.jwksUrl" -}}
{{- .Values.auth.jwksUrl | default (printf "https://login.microsoftonline.com/%s/discovery/v2.0/keys" .Values.auth.tenantId) -}}
{{- end -}}

{{/*
The one anchored expression that allows the proxy prefixes on the external API host, e.g. ^/(punishment|profile).
The tunnel (cloudflared) matches it as an unanchored regular expression against the request path.
*/}}
{{- define "blackhole.apiPathRegex" -}}
{{- printf "^/(%s)" (join "|" .Values.backend.externalApi.prefixes) -}}
{{- end -}}

{{/* Host and port of the scanner: an external one, or the Service of this release. */}}
{{- define "blackhole.clamdHost" -}}
{{- .Values.clamav.externalHost | default (include "blackhole.fullname" (dict "ctx" . "component" "clamav")) -}}
{{- end -}}

{{/* Whether this release renders a ClamAV workload. */}}
{{- define "blackhole.clamavWorkload" -}}
{{- if and .Values.clamav.enabled (not .Values.clamav.externalHost) -}}true{{- end -}}
{{- end -}}

{{/*
Cross-field checks a JSON schema cannot express. Included by templates/validate.yaml, which renders
nothing, so every render runs them.
*/}}
{{- define "blackhole.validate" -}}
{{- if and .Values.backend.geoip.enabled (not .Values.backend.geoip.existingClaim) -}}
{{- fail "backend.geoip.existingClaim is required when backend.geoip.enabled is true: the PVC that holds asn.mmdb and country.mmdb" -}}
{{- end -}}
{{- if and .Values.backend.geoip.refresh.enabled (not .Values.backend.geoip.enabled) -}}
{{- fail "backend.geoip.refresh.enabled needs backend.geoip.enabled: the refresh writes the files the backend reads" -}}
{{- end -}}
{{- if and .Values.backend.enabled .Values.web.enabled (ne (int64 .Values.backend.evidence.maxVideoBytes) (int64 .Values.web.evidence.maxVideoBytes)) -}}
{{- fail (printf "backend.evidence.maxVideoBytes (%d) and web.evidence.maxVideoBytes (%d) must be equal: the panel would accept uploads the backend refuses, or the other way round" (int64 .Values.backend.evidence.maxVideoBytes) (int64 .Values.web.evidence.maxVideoBytes)) -}}
{{- end -}}
{{- if and (not .Values.backend.enabled) (or .Values.web.enabled .Values.portal.enabled .Values.discord.enabled) -}}
{{- range $c := (list "web" "portal" "discord") -}}
{{- $v := index $.Values $c -}}
{{- if and $v.enabled (not $v.backendUrl) -}}
{{- fail (printf "%s.backendUrl is required when backend.enabled is false: there is no backend Service of this release to talk to" $c) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- with .Values.securityTxt -}}
{{- if .contact -}}
{{- if not .expires -}}
{{- fail "securityTxt.expires is required when securityTxt.contact is set: every deployment sets the expiry it has looked at (RFC 9116 Expires), there is no default" -}}
{{- end -}}
{{- if not (regexMatch "^[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?([Zz]|[+-][0-9]{2}:[0-9]{2})$" (toString .expires)) -}}
{{- fail (printf "securityTxt.expires must be an RFC 3339 timestamp with a time zone, e.g. 2027-06-30T12:00:00Z (got: %s)" (toString .expires)) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if and .Values.securityTxt.contact .Values.web.enabled (not .Values.web.origin) -}}
{{- fail "web.origin is required when securityTxt.contact is set: security.txt names its Canonical URL on the panel origin" -}}
{{- end -}}
{{- end -}}
