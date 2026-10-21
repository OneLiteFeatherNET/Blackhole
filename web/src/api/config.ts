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
}

export const NIL_UUID = '00000000-0000-0000-0000-000000000000'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function uuidOrNull(value: unknown): string | null {
  return typeof value === 'string' && UUID_PATTERN.test(value) ? value : null
}

/** Never throws: a missing or broken config.json only means calls are attributed to the nil UUID. */
export async function loadConfig(fetchImpl: typeof fetch = (input, init) => globalThis.fetch(input, init)): Promise<RuntimeConfig> {
  try {
    const response = await fetchImpl('/config.json', { cache: 'no-store' })
    if (response.ok) {
      const body = (await response.json()) as { source?: unknown; reviewer?: unknown } | null
      const source = uuidOrNull(body?.source)
      if (source) return { source, reviewer: uuidOrNull(body?.reviewer) ?? source }
    }
  } catch {
    // Fall through to the default.
  }
  return { source: NIL_UUID, reviewer: NIL_UUID }
}
