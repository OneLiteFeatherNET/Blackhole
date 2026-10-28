// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { ConfigError, parsePortalConfig } from './config'
import { loadPortalConfigOrExit } from './startup'

const PROD = { NODE_ENV: 'production', BLACKHOLE_PORTAL_ORIGIN: 'https://appeal.onelitefeather.net' }

class ExitCalled extends Error {
  readonly code: number

  constructor(code: number) {
    super(`exit ${code}`)
    this.code = code
  }
}

function deps() {
  const messages: string[] = []
  const warnings: string[] = []
  return {
    messages,
    warnings,
    exit: (code: number): never => {
      throw new ExitCalled(code)
    },
    error: (message: string) => messages.push(message),
    warn: (message: string) => warnings.push(message),
  }
}

describe('parsePortalConfig', () => {
  it('accepts the https portal origin and defaults the backend', () => {
    expect(parsePortalConfig(PROD)).toEqual({ backendUrl: 'http://backend:8080', portalOrigin: 'https://appeal.onelitefeather.net' })
  })

  it('strips trailing slashes of the backend URL', () => {
    expect(parsePortalConfig({ ...PROD, BLACKHOLE_BACKEND_URL: 'http://backend:8080//' }).backendUrl).toBe('http://backend:8080')
  })

  it('requires the portal origin', () => {
    expect(() => parsePortalConfig({ NODE_ENV: 'production' })).toThrow(ConfigError)
  })

  it.each([
    'http://appeal.onelitefeather.net',
    'https://appeal.onelitefeather.net/',
    'https://appeal.onelitefeather.net/path',
    'https://user@appeal.onelitefeather.net',
    'https://appeal.onelitefeather.net?x=1',
    'appeal.onelitefeather.net',
    'http://localhost:5180',
  ])('refuses the origin %s in production', (origin) => {
    expect(() => parsePortalConfig({ ...PROD, BLACKHOLE_PORTAL_ORIGIN: origin })).toThrow(/BLACKHOLE_PORTAL_ORIGIN/)
  })

  it('accepts a plain http loopback origin outside production', () => {
    expect(parsePortalConfig({ BLACKHOLE_PORTAL_ORIGIN: 'http://localhost:5180' }).portalOrigin).toBe('http://localhost:5180')
  })

  it('still refuses plain http for a real host outside production', () => {
    expect(() => parsePortalConfig({ BLACKHOLE_PORTAL_ORIGIN: 'http://appeal.example.org' })).toThrow(/BLACKHOLE_PORTAL_ORIGIN/)
  })

  it.each(['ftp://x', 'http://user:pw@backend', 'http://backend:8080?x=1', 'not a url'])('refuses the backend URL %s', (url) => {
    expect(() => parsePortalConfig({ ...PROD, BLACKHOLE_BACKEND_URL: url })).toThrow(/BLACKHOLE_BACKEND_URL/)
  })

  it('names every offending variable', () => {
    try {
      parsePortalConfig({ BLACKHOLE_BACKEND_URL: 'ftp://x' })
      expect.unreachable()
    } catch (error) {
      expect((error as ConfigError).problems).toHaveLength(2)
    }
  })
})

describe('loadPortalConfigOrExit', () => {
  it('returns the configuration for a valid environment', () => {
    const d = deps()

    expect(loadPortalConfigOrExit(PROD, d).portalOrigin).toBe('https://appeal.onelitefeather.net')
    expect(d.messages, 'nothing logged').toEqual([])
  })

  it('exits with code 1 before listening for an invalid environment', () => {
    expect(() => loadPortalConfigOrExit({}, deps())).toThrow(expect.objectContaining({ code: 1 }))
  })

  it('writes every offending variable to stderr before exiting', () => {
    const d = deps()

    expect(() => loadPortalConfigOrExit({ BLACKHOLE_BACKEND_URL: 'ftp://x' }, d)).toThrow(ExitCalled)

    const output = d.messages.join('\n')
    expect(output).toContain('BLACKHOLE_BACKEND_URL')
    expect(output).toContain('BLACKHOLE_PORTAL_ORIGIN')
    expect(output).toContain('refusing to start')
  })
})

describe('security.txt settings', () => {
  const NOW = new Date('2026-10-28T12:00:00Z')
  const SECURITY = { BLACKHOLE_SECURITY_CONTACT: 'mailto:security@onelitefeather.net', BLACKHOLE_SECURITY_EXPIRES: '2027-06-30T12:00:00Z' }

  it('carries the security.txt value when a contact is configured', () => {
    expect(parsePortalConfig({ ...PROD, ...SECURITY }, NOW).securityTxt?.canonical).toBe(
      'https://appeal.onelitefeather.net/.well-known/security.txt',
    )
  })

  it('leaves security.txt disabled without a contact', () => {
    expect(parsePortalConfig(PROD, NOW).securityTxt).toBeUndefined()
  })

  it('refuses an invalid security setting together with the other settings and names it', () => {
    try {
      parsePortalConfig({ ...PROD, BLACKHOLE_SECURITY_CONTACT: 'security@onelitefeather.net', BLACKHOLE_SECURITY_EXPIRES: '2027-06-30T12:00:00Z' }, NOW)
      expect.unreachable()
    } catch (error) {
      expect((error as ConfigError).problems.join('\n')).toContain('BLACKHOLE_SECURITY_CONTACT')
    }
  })

  it('logs one warning naming the contact variable when security.txt is disabled', () => {
    const d = deps()
    loadPortalConfigOrExit(PROD, { ...d, now: () => NOW })
    expect(d.warnings.filter((w) => w.includes('BLACKHOLE_SECURITY_CONTACT'))).toHaveLength(1)
  })

  it('does not warn when security.txt is enabled', () => {
    const d = deps()
    loadPortalConfigOrExit({ ...PROD, ...SECURITY }, { ...d, now: () => NOW })
    expect(d.warnings).toEqual([])
  })

  it('exits with code 1 when the expiry is already in the past at startup', () => {
    const d = deps()
    const env = { ...PROD, BLACKHOLE_SECURITY_CONTACT: SECURITY.BLACKHOLE_SECURITY_CONTACT, BLACKHOLE_SECURITY_EXPIRES: '2026-01-01T00:00:00Z' }
    expect(() => loadPortalConfigOrExit(env, { ...d, now: () => NOW })).toThrow(expect.objectContaining({ code: 1 }))
    expect(d.messages.join('\n')).toContain('BLACKHOLE_SECURITY_EXPIRES')
  })
})
