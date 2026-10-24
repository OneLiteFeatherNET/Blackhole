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
  return {
    messages,
    exit: (code: number): never => {
      throw new ExitCalled(code)
    },
    error: (message: string) => messages.push(message),
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
