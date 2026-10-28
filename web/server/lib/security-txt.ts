/**
 * RFC 9116 `security.txt`, built from BLACKHOLE_SECURITY_* settings. Pure functions: the clock is a
 * parameter, so tests never read the system time.
 */

export interface SecurityTxt {
  contact: string
  expires: string
  encryption?: string
  acknowledgments?: string
  policy?: string
  preferredLanguages: string
  /** `<origin>/.well-known/security.txt` on the portal's own origin. */
  canonical: string
}

export interface SecurityTxtParse {
  /** Undefined when BLACKHOLE_SECURITY_CONTACT is unset: the route is disabled, not an error. */
  value: SecurityTxt | undefined
  /** One message per invalid variable, each naming the variable. */
  problems: string[]
}

type Env = Record<string, string | undefined>

const DEFAULT_LANGUAGES = 'en, de'
const WELL_KNOWN_PATH = '/.well-known/security.txt'
const RFC3339_WITH_ZONE = /^\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})$/
const MAILTO = /^mailto:[^\s]+@[^\s]+$/i
const TEL = /^tel:\+[0-9().-]{4,}$/i
const LANGUAGE_TAG = /^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$/

/** An https URI without whitespace or credentials, or undefined. */
function parseHttpsUri(raw: string): string | undefined {
  if (/\s/.test(raw)) return undefined
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return undefined
  }
  if (url.protocol !== 'https:' || url.username || url.password) return undefined
  return raw
}

function isContact(raw: string): boolean {
  return MAILTO.test(raw) || TEL.test(raw) || parseHttpsUri(raw) !== undefined
}

/** True for a timestamp with a time zone that is not in the past and not more than one calendar year ahead. */
function expiryProblem(raw: string, now: Date): string | undefined {
  if (!RFC3339_WITH_ZONE.test(raw) || Number.isNaN(Date.parse(raw))) {
    return 'an RFC 3339 timestamp with a time zone, e.g. 2027-06-30T12:00:00Z'
  }
  const expires = new Date(raw)
  if (expires.getTime() <= now.getTime()) return 'in the future: the value is in the past'
  const oneYearAhead = new Date(now.getTime())
  oneYearAhead.setUTCFullYear(oneYearAhead.getUTCFullYear() + 1)
  if (expires.getTime() > oneYearAhead.getTime()) return 'at most one calendar year ahead'
  return undefined
}

/**
 * Reads and validates the security settings. `origin` is the app's own public origin, already validated
 * by the caller. Returns every problem; `value` is set only when there are none.
 */
export function parseSecurityTxt(env: Env, origin: string, now: Date): SecurityTxtParse {
  const problems: string[] = []
  const contact = env.BLACKHOLE_SECURITY_CONTACT?.trim() ?? ''
  if (contact === '') return { value: undefined, problems }

  if (!isContact(contact)) {
    problems.push(
      `BLACKHOLE_SECURITY_CONTACT must be a mailto:, tel: or https: URI, e.g. mailto:security@onelitefeather.net (got: '${contact}')`,
    )
  }

  const expires = env.BLACKHOLE_SECURITY_EXPIRES?.trim() ?? ''
  if (expires === '') {
    problems.push('BLACKHOLE_SECURITY_EXPIRES must be set when BLACKHOLE_SECURITY_CONTACT is set')
  } else {
    const reason = expiryProblem(expires, now)
    if (reason !== undefined) problems.push(`BLACKHOLE_SECURITY_EXPIRES must be ${reason} (got: '${expires}')`)
  }

  const optionalLinks = {
    encryption: parseOptionalHttps(env, 'BLACKHOLE_SECURITY_ENCRYPTION', problems),
    acknowledgments: parseOptionalHttps(env, 'BLACKHOLE_SECURITY_ACKNOWLEDGMENTS', problems),
    policy: parseOptionalHttps(env, 'BLACKHOLE_SECURITY_POLICY', problems),
  }

  const rawLanguages = env.BLACKHOLE_SECURITY_PREFERRED_LANGUAGES?.trim() || DEFAULT_LANGUAGES
  const languages = rawLanguages.split(',').map((tag) => tag.trim())
  const languagesValid = languages.every((tag) => LANGUAGE_TAG.test(tag))
  if (!languagesValid) {
    problems.push(
      `BLACKHOLE_SECURITY_PREFERRED_LANGUAGES must be a comma-separated list of language tags, e.g. en, de (got: '${rawLanguages}')`,
    )
  }

  if (problems.length > 0) return { value: undefined, problems }
  return {
    value: {
      contact,
      expires,
      ...stripUndefined(optionalLinks),
      preferredLanguages: languages.join(', '),
      canonical: `${origin}${WELL_KNOWN_PATH}`,
    },
    problems,
  }
}

function parseOptionalHttps(env: Env, name: string, problems: string[]): string | undefined {
  const raw = env[name]?.trim() ?? ''
  if (raw === '') return undefined
  const uri = parseHttpsUri(raw)
  if (uri === undefined) problems.push(`${name} must be an https URI without credentials (got: '${raw}')`)
  return uri
}

function stripUndefined<T extends object>(source: T): Partial<T> {
  return Object.fromEntries(Object.entries(source).filter(([, v]) => v !== undefined)) as Partial<T>
}

/** The file body, one field per line in RFC 9116 field names. */
export function renderSecurityTxt(value: SecurityTxt): string {
  const lines = [
    `Contact: ${value.contact}`,
    `Expires: ${value.expires}`,
    value.encryption !== undefined ? `Encryption: ${value.encryption}` : undefined,
    value.acknowledgments !== undefined ? `Acknowledgments: ${value.acknowledgments}` : undefined,
    value.policy !== undefined ? `Policy: ${value.policy}` : undefined,
    `Preferred-Languages: ${value.preferredLanguages}`,
    `Canonical: ${value.canonical}`,
  ]
  return `${lines.filter((line): line is string => line !== undefined).join('\n')}\n`
}

export interface SecurityTxtResponse {
  status: number
  headers: Record<string, string>
  body: string
}

/** The HTTP answer for the route: the file, or 404 when the route is disabled. */
export function securityTxtResponse(value: SecurityTxt | undefined): SecurityTxtResponse {
  if (value === undefined) return { status: 404, headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: '404 Not Found' }
  return {
    status: 200,
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
      'Cache-Control': 'public, max-age=86400',
    },
    body: renderSecurityTxt(value),
  }
}
