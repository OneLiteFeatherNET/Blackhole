{{/*
The Ingress (public hosts through the Cloudflare tunnel) or the HTTPRoute (internal hosts through the
Envoy Gateway) of the panel and the portal. The schema allows only one of the two per component.
dict: ctx, component, values
*/}}
{{- define "blackhole.exposure" -}}
{{- $name := include "blackhole.fullname" . -}}
{{- if .values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ $name }}
  namespace: {{ .ctx.Release.Namespace }}
  labels:
    {{- include "blackhole.labels" . | nindent 4 }}
  annotations:
    {{- /* Values win over the default; the tunnel controller must talk plain http to the Service. */}}
    {{- toYaml (merge (dict) (.values.ingress.annotations | default dict) (dict "cloudflare-tunnel-ingress-controller.strrl.dev/backend-protocol" "http")) | nindent 4 }}
spec:
  ingressClassName: {{ .values.ingress.className }}
  rules:
    - host: {{ .values.ingress.host }}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: {{ $name }}
                port:
                  name: http
{{- end }}
{{- if .values.httpRoute.enabled }}
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: {{ $name }}
  namespace: {{ .ctx.Release.Namespace }}
  labels:
    {{- include "blackhole.labels" . | nindent 4 }}
spec:
  parentRefs:
    {{- toYaml .values.httpRoute.parentRefs | nindent 4 }}
  hostnames:
    {{- toYaml .values.httpRoute.hostnames | nindent 4 }}
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /
      backendRefs:
        - name: {{ $name }}
          port: {{ .values.service.port }}
{{- end }}
{{- end -}}
