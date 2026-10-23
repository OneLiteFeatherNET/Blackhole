/** Runtime settings of the UI server, read once from the environment at startup. */

export interface UiConfig {
  /** Where `/api/**` is forwarded to, without a trailing slash. Never sent to the browser. */
  backendUrl: string
  source: string
  reviewer: string
  evidence?: { maxImageBytes?: number; maxVideoBytes?: number; allowedTypes?: string[] }
}

/** What `GET /config.json` returns: everything except the backend URL. */
export type PublicUiConfig = Omit<UiConfig, 'backendUrl'>

export class ConfigError extends Error {
  readonly problems: string[]

  constructor(problems: string[]) {
    super(problems.join('\n'))
    this.name = 'ConfigError'
    this.problems = problems
  }
}

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
const BYTES = /^[1-9][0-9]{0,15}$/
const MIME_LIST = /^[a-z0-9.+-]+\/[a-z0-9.+-]+(,[a-z0-9.+-]+\/[a-z0-9.+-]+)*$/
const DEFAULT_BACKEND_URL = 'http://backend:8080'

type Env = Record<string, string | undefined>

function parseBackendUrl(raw: string): string | undefined {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return undefined
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return undefined
  if (url.username || url.password || raw.includes('?') || raw.includes('#')) return undefined
  return raw.replace(/\/+$/, '')
}

/**
 * Validates the environment with the same strict patterns the former nginx start script used.
 * Throws a {@link ConfigError} naming every invalid variable.
 */
export function parseUiConfig(env: Env): UiConfig {
  const problems: string[] = []
  const get = (name: string) => env[name] ?? ''

  const rawBackend = get('BLACKHOLE_BACKEND_URL') || DEFAULT_BACKEND_URL
  const backendUrl = parseBackendUrl(rawBackend)
  if (backendUrl === undefined) {
    problems.push(
      `BLACKHOLE_BACKEND_URL must be an http or https URL without credentials, query or fragment, e.g. http://backend:8080 (got: '${rawBackend}')`,
    )
  }

  const source = get('BLACKHOLE_UI_SOURCE')
  const reviewer = get('BLACKHOLE_UI_REVIEWER') || source
  for (const [name, value] of [
    ['BLACKHOLE_UI_SOURCE', source],
    ['BLACKHOLE_UI_REVIEWER', reviewer],
  ] as const) {
    if (!UUID.test(value)) {
      problems.push(`${name} must be a UUID, e.g. 3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b (got: '${value}')`)
    }
  }

  const evidence: NonNullable<UiConfig['evidence']> = {}
  for (const [name, key] of [
    ['BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES', 'maxImageBytes'],
    ['BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES', 'maxVideoBytes'],
  ] as const) {
    const value = get(name)
    if (!value) continue
    if (BYTES.test(value)) evidence[key] = Number(value)
    else problems.push(`${name} must be a positive number of bytes (got: '${value}')`)
  }
  const types = get('BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES')
  if (types) {
    if (MIME_LIST.test(types)) evidence.allowedTypes = types.split(',')
    else problems.push(`BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES must be comma separated MIME types, e.g. image/png,video/mp4 (got: '${types}')`)
  }

  if (problems.length > 0 || backendUrl === undefined) throw new ConfigError(problems)
  return { backendUrl, source, reviewer, ...(Object.keys(evidence).length > 0 ? { evidence } : {}) }
}

export function toPublicConfig(config: UiConfig): PublicUiConfig {
  const { source, reviewer, evidence } = config
  return { source, reviewer, ...(evidence ? { evidence } : {}) }
}
