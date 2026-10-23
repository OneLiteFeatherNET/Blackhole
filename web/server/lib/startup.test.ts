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
})
