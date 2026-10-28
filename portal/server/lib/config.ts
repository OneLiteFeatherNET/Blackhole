import { parseSecurityTxt, type SecurityTxt } from './security-txt'

/** Runtime settings of the portal server, read once from the environment at startup. */

export interface PortalConfig {
  /** Where the allow-listed routes are forwarded to, without a trailing slash. Never sent to the browser. */
  backendUrl: string
  /** The origin the portal is served at (`https://appeal.onelitefeather.net`): the only accepted `Origin` of a state-changing request. */
  portalOrigin: string
  /** The RFC 9116 file served at `/.well-known/security.txt`; undefined when BLACKHOLE_SECURITY_CONTACT is unset. */
  securityTxt: SecurityTxt | undefined
}

export class ConfigError extends Error {
  readonly problems: string[]

  constructor(problems: string[]) {
    super(problems.join('\n'))
    this.name = 'ConfigError'
    this.problems = problems
  }
}

type Env = Record<string, string | undefined>

const DEFAULT_BACKEND_URL = 'http://backend:8080'
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]'])

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

/** A bare origin: scheme, host, optional port, nothing else. https only; http for loopback outside production. */
function parsePortalOrigin(raw: string, production: boolean): string | undefined {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return undefined
  }
  const bare = raw === url.origin
  const secure = url.protocol === 'https:' || (!production && url.protocol === 'http:' && LOOPBACK_HOSTS.has(url.hostname))
  return bare && secure ? url.origin : undefined
}

/**
 * Validates the environment with strict patterns.
 * Throws a {@link ConfigError} naming every invalid variable. `now` is the clock the Expires window is checked against.
 */
export function parsePortalConfig(env: Env, now: Date = new Date()): PortalConfig {
  const problems: string[] = []
  const production = env.NODE_ENV === 'production'

  const rawBackend = env.BLACKHOLE_BACKEND_URL || DEFAULT_BACKEND_URL
  const backendUrl = parseBackendUrl(rawBackend)
  if (backendUrl === undefined) {
    problems.push(
      `BLACKHOLE_BACKEND_URL must be an http or https URL without credentials, query or fragment, e.g. http://backend:8080 (got: '${rawBackend}')`,
    )
  }

  const rawOrigin = env.BLACKHOLE_PORTAL_ORIGIN ?? ''
  const portalOrigin = parsePortalOrigin(rawOrigin, production)
  if (portalOrigin === undefined) {
    problems.push(
      `BLACKHOLE_PORTAL_ORIGIN must be the https origin the portal is served at, without path, e.g. https://appeal.onelitefeather.net (http only for localhost outside production) (got: '${rawOrigin}')`,
    )
  }

  const security = parseSecurityTxt(env, portalOrigin ?? '', now)
  problems.push(...security.problems)

  if (problems.length > 0 || backendUrl === undefined || portalOrigin === undefined) throw new ConfigError(problems)
  return { backendUrl, portalOrigin, securityTxt: security.value }
}
