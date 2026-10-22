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

/** Per-deployment settings, written by the container at start into /config.json. */
export interface RuntimeConfig {
  /** The operator UUID sent as `source`/`resolvedBy`/`punishmentSource`. Unverified, see the identity gap. */
  source: string
  /**
   * The UUID sent as `reviewerId` when deciding an appeal. Defaults to `source`; the backend refuses a
   * review whose reviewer issued the punishment, so deployments that decide appeals against
   * punishments applied through this UI need a different value (see the web README).
   */
  reviewer: string
  /** Upload pre-check limits; each missing or invalid value falls back to the backend default. */
  evidence: EvidenceLimits
}

export const NIL_UUID = '00000000-0000-0000-0000-000000000000'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function uuidOrNull(value: unknown): string | null {
  return typeof value === 'string' && UUID_PATTERN.test(value) ? value : null
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

/** Never throws: a missing or broken config.json only means calls are attributed to the nil UUID. */
export async function loadConfig(fetchImpl: typeof fetch = (input, init) => globalThis.fetch(input, init)): Promise<RuntimeConfig> {
  try {
    const response = await fetchImpl('/config.json', { cache: 'no-store' })
    if (response.ok) {
      const body = (await response.json()) as { source?: unknown; reviewer?: unknown; evidence?: unknown } | null
      const source = uuidOrNull(body?.source)
      if (source) return { source, reviewer: uuidOrNull(body?.reviewer) ?? source, evidence: evidenceLimits(body?.evidence) }
    }
  } catch {
    // Fall through to the default.
  }
  return { source: NIL_UUID, reviewer: NIL_UUID, evidence: evidenceLimits(null) }
}
