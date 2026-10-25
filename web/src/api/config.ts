import type { OidcConfig } from '../auth/authService'

/** What the evidence upload zone checks before sending a file; advisory, the backend is authoritative. */
export interface EvidenceLimits {
  /** Largest image in bytes. */
  maxImageBytes: number
  /** Largest video in bytes. */
  maxVideoBytes: number
  /** Allowed MIME types, e.g. `image/png`. */
  allowedTypes: string[]
}

/** The backend defaults (`blackhole.evidence.files.*` in application.yml). */
export const DEFAULT_EVIDENCE_LIMITS: EvidenceLimits = {
  maxImageBytes: 10_485_760,
  maxVideoBytes: 104_857_600,
  allowedTypes: ['image/png', 'image/jpeg', 'video/mp4', 'video/webm'],
}

/** Per-deployment settings, served by the web server as /config.json. */
export interface RuntimeConfig {
  /** How staff sign in; null when /config.json is missing or has no valid `oidc` section. */
  oidc: OidcConfig | null
  /** Upload pre-check limits; each missing or invalid value falls back to the backend default. */
  evidence: EvidenceLimits
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null
}

function oidcConfig(value: unknown): OidcConfig | null {
  const body = (typeof value === 'object' && value !== null ? value : {}) as Record<string, unknown>
  const authority = text(body.authority)
  const clientId = text(body.clientId)
  const apiScope = text(body.apiScope)
  const panelOrigin = text(body.panelOrigin)
  return authority && clientId && apiScope ? { authority, clientId, apiScope, ...(panelOrigin ? { panelOrigin } : {}) } : null
}

function positiveInteger(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : fallback
}

function evidenceLimits(value: unknown): EvidenceLimits {
  const body = (typeof value === 'object' && value !== null ? value : {}) as Record<string, unknown>
  const types = Array.isArray(body.allowedTypes)
    ? body.allowedTypes.filter((type): type is string => typeof type === 'string' && type.trim() !== '').map((type) => type.trim().toLowerCase())
    : []
  return {
    maxImageBytes: positiveInteger(body.maxImageBytes, DEFAULT_EVIDENCE_LIMITS.maxImageBytes),
    maxVideoBytes: positiveInteger(body.maxVideoBytes, DEFAULT_EVIDENCE_LIMITS.maxVideoBytes),
    allowedTypes: types.length > 0 ? types : [...DEFAULT_EVIDENCE_LIMITS.allowedTypes],
  }
}

/** Never throws: a missing or broken config.json only means sign-in is not configured. */
export async function loadConfig(fetchImpl: typeof fetch = (input, init) => globalThis.fetch(input, init)): Promise<RuntimeConfig> {
  try {
    const response = await fetchImpl('/config.json', { cache: 'no-store' })
    if (response.ok) {
      const body = (await response.json()) as { oidc?: unknown; evidence?: unknown } | null
      return { oidc: oidcConfig(body?.oidc), evidence: evidenceLimits(body?.evidence) }
    }
  } catch {
    // Fall through to the default.
  }
  return { oidc: null, evidence: evidenceLimits(null) }
}
