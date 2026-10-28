// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { loadUiConfigOrExit } from './startup'

class ExitCalled extends Error {
  readonly code: number

  constructor(code: number) {
    super(`exit ${code}`)
    this.code = code
  }
}

const NOW = new Date('2026-10-28T12:00:00Z')
const OIDC = {
  BLACKHOLE_UI_OIDC_AUTHORITY: 'https://login.example.org/tenant/v2.0',
  BLACKHOLE_UI_OIDC_CLIENT_ID: 'spa',
  BLACKHOLE_UI_API_SCOPE: 'api://x/access_as_user',
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
    now: () => NOW,
  }
}

describe('loadUiConfigOrExit', () => {
  it('returns the parsed configuration for a valid environment', () => {
    const d = deps()

    const config = loadUiConfigOrExit(
      {
        BLACKHOLE_UI_OIDC_AUTHORITY: 'https://login.example.org/tenant/v2.0',
        BLACKHOLE_UI_OIDC_CLIENT_ID: 'spa',
        BLACKHOLE_UI_API_SCOPE: 'api://x/access_as_user',
      },
      d,
    )

    expect(config.oidc.clientId).toBe('spa')
    expect(d.messages, 'nothing logged').toEqual([])
  })

  it('exits with code 1 for an invalid environment', () => {
    const d = deps()

    expect(() => loadUiConfigOrExit({}, d)).toThrow(expect.objectContaining({ code: 1 }))
  })

  it('writes every offending variable to stderr before exiting', () => {
    const d = deps()

    expect(() => loadUiConfigOrExit({ BLACKHOLE_BACKEND_URL: 'ftp://x', BLACKHOLE_UI_OIDC_CLIENT_ID: '' }, d)).toThrow(ExitCalled)

    const output = d.messages.join('\n')
    expect(output).toContain('BLACKHOLE_BACKEND_URL')
    expect(output).toContain('BLACKHOLE_UI_OIDC_CLIENT_ID')
    expect(output).toContain('refusing to start')
  })

  it('logs one warning naming the contact variable when security.txt is disabled', () => {
    const d = deps()

    loadUiConfigOrExit(OIDC, d)

    expect(d.warnings.filter((w) => w.includes('BLACKHOLE_SECURITY_CONTACT'))).toHaveLength(1)
  })

  it('exits with code 1 when the security.txt expiry is already in the past', () => {
    const d = deps()
    const env = {
      ...OIDC,
      BLACKHOLE_UI_ORIGIN: 'https://blackhole.onelitefeather.net',
      BLACKHOLE_SECURITY_CONTACT: 'mailto:security@onelitefeather.net',
      BLACKHOLE_SECURITY_EXPIRES: '2026-01-01T00:00:00Z',
    }

    expect(() => loadUiConfigOrExit(env, d)).toThrow(expect.objectContaining({ code: 1 }))
    expect(d.messages.join('\n')).toContain('BLACKHOLE_SECURITY_EXPIRES')
  })
})
