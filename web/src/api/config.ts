/** Per-deployment settings, written by the container at start into /config.json. */
export interface RuntimeConfig {
  /** The operator UUID sent as `source`/`resolvedBy`/`reviewerId`. Unverified, see the identity gap. */
  source: string
}

export const NIL_UUID = '00000000-0000-0000-0000-000000000000'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Never throws: a missing or broken config.json only means calls are attributed to the nil UUID. */
export async function loadConfig(fetchImpl: typeof fetch = (input, init) => globalThis.fetch(input, init)): Promise<RuntimeConfig> {
  try {
    const response = await fetchImpl('/config.json', { cache: 'no-store' })
    if (response.ok) {
      const body: unknown = await response.json()
      const source = typeof body === 'object' && body !== null ? (body as { source?: unknown }).source : undefined
      if (typeof source === 'string' && UUID_PATTERN.test(source)) return { source }
    }
  } catch {
    // Fall through to the default.
  }
  return { source: NIL_UUID }
}
