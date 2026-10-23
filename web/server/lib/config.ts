/** Runtime settings of the UI server, read once from the environment at startup. */

export interface OidcConfig {
  /** The issuer URL without a trailing slash, e.g. `https://login.microsoftonline.com/<tenant>/v2.0`. */
  authority: string
  clientId: string
  /** The API scope the access token is requested for, e.g. `api://<api-client-id>/access_as_user`. */
  apiScope: string
}

export interface UiConfig {
  /** Where `/api/**` is forwarded to, without a trailing slash. Never sent to the browser. */
  backendUrl: string
  /** Where staff sign in; the browser needs all three, none is a secret. */
  oidc: OidcConfig
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

const CLIENT_ID = /^[A-Za-z0-9._-]{1,128}$/
const API_SCOPE = /^[A-Za-z0-9._:/-]{1,256}$/
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

const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]'])

/** https only; plain http is accepted for a loopback host so a local mock server works. */
function parseAuthority(raw: string): string | undefined {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return undefined
  }
  const secure = url.protocol === 'https:' || (url.protocol === 'http:' && LOOPBACK_HOSTS.has(url.hostname))
  if (!secure || url.username || url.password || raw.includes('?') || raw.includes('#')) return undefined
  return raw.replace(/\/+$/, '')
}

/**
 * Validates the environment with strict patterns.
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

  const rawAuthority = get('BLACKHOLE_UI_OIDC_AUTHORITY')
  const authority = parseAuthority(rawAuthority)
  if (authority === undefined) {
    problems.push(
      `BLACKHOLE_UI_OIDC_AUTHORITY must be an https URL (http only for localhost) without credentials, query or fragment, e.g. https://login.microsoftonline.com/<tenant>/v2.0 (got: '${rawAuthority}')`,
    )
  }
  const clientId = get('BLACKHOLE_UI_OIDC_CLIENT_ID')
  if (!CLIENT_ID.test(clientId)) {
    problems.push(`BLACKHOLE_UI_OIDC_CLIENT_ID must be the SPA's application (client) id, letters, digits, '.', '_' or '-' (got: '${clientId}')`)
  }
  const apiScope = get('BLACKHOLE_UI_API_SCOPE')
  if (!API_SCOPE.test(apiScope)) {
    problems.push(`BLACKHOLE_UI_API_SCOPE must be one scope without spaces, e.g. api://<api-client-id>/access_as_user (got: '${apiScope}')`)
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

  if (problems.length > 0 || backendUrl === undefined || authority === undefined) throw new ConfigError(problems)
  return { backendUrl, oidc: { authority, clientId, apiScope }, ...(Object.keys(evidence).length > 0 ? { evidence } : {}) }
}

export function toPublicConfig(config: UiConfig): PublicUiConfig {
  const { oidc, evidence } = config
  return { oidc, ...(evidence ? { evidence } : {}) }
}
