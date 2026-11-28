// An integration detection as the panel receives it. The payload is already validated by the backend against the
// schema of (sourceSystem, kind, schemaVersion); the panel still treats every string in it as text only.
export interface IntegrationDetection {
  id: string
  sourceSystem: string
  kind: string
  schemaVersion: number
  occurredAt: string
  server: string
  severity: string | null
  summary: string
  payload: Record<string, unknown>
}

// The key a renderer is registered under: "<sourceSystem>/<kind>@<schemaVersion>", for example vulcan/DETECTION@1.
export function rendererKey(detection: Pick<IntegrationDetection, 'sourceSystem' | 'kind' | 'schemaVersion'>): string {
  return `${detection.sourceSystem}/${detection.kind}@${detection.schemaVersion}`
}
