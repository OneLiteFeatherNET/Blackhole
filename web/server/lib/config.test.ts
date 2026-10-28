// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ConfigError, parseUiConfig, toPublicConfig } from './config'

const AUTHORITY = 'https://login.microsoftonline.com/11111111-2222-4333-8444-555555555555/v2.0'
const CLIENT_ID = '22222222-3333-4444-8555-666666666666'
const API_SCOPE = 'api://33333333-4444-4555-8666-777777777777/access_as_user'
const OIDC = {
  BLACKHOLE_UI_OIDC_AUTHORITY: AUTHORITY,
  BLACKHOLE_UI_OIDC_CLIENT_ID: CLIENT_ID,
  BLACKHOLE_UI_API_SCOPE: API_SCOPE,
}

const NOW = new Date('2026-10-28T12:00:00Z')

function problemsOf(env: Record<string, string>): string[] {
  try {
    parseUiConfig(env, NOW)
  } catch (error) {
    expect(error, 'a ConfigError is thrown').toBeInstanceOf(ConfigError)
    return (error as ConfigError).problems
  }
  throw new Error('expected parseUiConfig to throw')
}

describe('parseUiConfig', () => {
  it('reads the sign-in settings', () => {
    expect(parseUiConfig(OIDC).oidc).toEqual({ authority: AUTHORITY, clientId: CLIENT_ID, apiScope: API_SCOPE })
  })

  it('strips trailing slashes from the authority', () => {
    expect(parseUiConfig({ ...OIDC, BLACKHOLE_UI_OIDC_AUTHORITY: `${AUTHORITY}//` }).oidc.authority).toBe(AUTHORITY)
  })

  it.each(['BLACKHOLE_UI_OIDC_AUTHORITY', 'BLACKHOLE_UI_OIDC_CLIENT_ID', 'BLACKHOLE_UI_API_SCOPE'])('refuses to start without %s', (name) => {
    const { [name]: _removed, ...rest } = OIDC as Record<string, string>

    expect(problemsOf(rest).join('\n')).toContain(`${name} must be`)
  })

  it.each(['http://login.example.org/v2.0', 'ftp://login.example.org', 'login.example.org', 'https://user:pw@login.example.org', 'https://login.example.org/v2.0?x=1', 'https://login.example.org/v2.0#frag', 'javascript:alert(1)'])(
    'refuses the authority %j',
    (value) => {
      expect(problemsOf({ ...OIDC, BLACKHOLE_UI_OIDC_AUTHORITY: value }).join('\n')).toContain('BLACKHOLE_UI_OIDC_AUTHORITY must be')
    },
  )

  it.each(['http://localhost:18090/blackhole', 'http://127.0.0.1:18090/blackhole', 'http://[::1]:18090/blackhole'])('accepts the loopback authority %s over http', (value) => {
    expect(parseUiConfig({ ...OIDC, BLACKHOLE_UI_OIDC_AUTHORITY: value }).oidc.authority).toBe(value)
  })

  it.each(['', 'a b', 'x","y":"z', 'x'.repeat(129)])('refuses the client id %j, so nothing can be injected into the JSON', (value) => {
    expect(problemsOf({ ...OIDC, BLACKHOLE_UI_OIDC_CLIENT_ID: value }).join('\n')).toContain('BLACKHOLE_UI_OIDC_CLIENT_ID must be')
  })

  it.each(['', 'openid profile', 'api://x/y","z":"1', '"quoted"'])('refuses the API scope %j', (value) => {
    expect(problemsOf({ ...OIDC, BLACKHOLE_UI_API_SCOPE: value }).join('\n')).toContain('BLACKHOLE_UI_API_SCOPE must be')
  })

  it('no longer reads the operator and reviewer UUIDs', () => {
    const config = parseUiConfig({ ...OIDC, BLACKHOLE_UI_SOURCE: 'bad', BLACKHOLE_UI_REVIEWER: 'bad' })

    expect(Object.keys(config), 'config keys').not.toContain('source')
    expect(Object.keys(config), 'config keys').not.toContain('reviewer')
  })

  it('reads the evidence upload limits when they are set', () => {
    const config = parseUiConfig({
      ...OIDC,
      BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES: '2048',
      BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES: '4096',
      BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES: 'image/png,video/mp4',
    })

    expect(config.evidence).toEqual({ maxImageBytes: 2048, maxVideoBytes: 4096, allowedTypes: ['image/png', 'video/mp4'] })
  })

  it('leaves evidence out when no limit is set', () => {
    expect(parseUiConfig({ ...OIDC }).evidence, 'evidence').toBeUndefined()
  })

  it.each(['0', '-1', '1.5', '01', '1,"x":"y', 'abc', '12345678901234567'])('refuses the evidence limit %j', (value) => {
    expect(problemsOf({ ...OIDC, BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES: value }).join('\n')).toContain(
      'BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES must be a positive number',
    )
  })

  it('refuses allowed types that are not MIME types', () => {
    expect(problemsOf({ ...OIDC, BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES: 'image/png","x":"y' }).join('\n')).toContain(
      'BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES must be comma separated MIME types',
    )
  })

  it('defaults the backend URL to http://backend:8080', () => {
    expect(parseUiConfig({ ...OIDC }).backendUrl).toBe('http://backend:8080')
  })

  it('strips trailing slashes from the backend URL', () => {
    expect(parseUiConfig({ ...OIDC, BLACKHOLE_BACKEND_URL: 'http://other:9000//' }).backendUrl).toBe('http://other:9000')
  })

  it('accepts an https backend URL with a path prefix', () => {
    expect(parseUiConfig({ ...OIDC, BLACKHOLE_BACKEND_URL: 'https://api.example.org/base/' }).backendUrl).toBe(
      'https://api.example.org/base',
    )
  })

  it.each(['ftp://backend', 'backend:8080', 'not a url', 'http://user:pw@backend', 'http://backend?x=1', 'http://backend#frag', 'file:///etc/passwd'])(
    'refuses the backend URL %j',
    (value) => {
      expect(problemsOf({ ...OIDC, BLACKHOLE_BACKEND_URL: value }).join('\n')).toContain('BLACKHOLE_BACKEND_URL must be')
    },
  )

  it('names every invalid variable in one error', () => {
    const problems = problemsOf({
      BLACKHOLE_BACKEND_URL: 'ftp://x',
      BLACKHOLE_UI_SOURCE: 'bad',
      BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES: '0',
      BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES: 'x',
      BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES: 'nope',
    })

    for (const name of [
      'BLACKHOLE_BACKEND_URL',
      'BLACKHOLE_UI_OIDC_AUTHORITY',
      'BLACKHOLE_UI_OIDC_CLIENT_ID',
      'BLACKHOLE_UI_API_SCOPE',
      'BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES',
      'BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES',
      'BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES',
    ]) {
      expect(problems.some((p) => p.startsWith(name)), `${name} is named`).toBe(true)
    }
  })
})

describe('toPublicConfig', () => {
  it('does not expose the backend URL', () => {
    const config = parseUiConfig({ ...OIDC, BLACKHOLE_BACKEND_URL: 'http://internal:8080' })

    expect(toPublicConfig(config)).toEqual({ oidc: { authority: AUTHORITY, clientId: CLIENT_ID, apiScope: API_SCOPE } })
  })
})

describe('web README', () => {
  it('documents every evidence variable and config key the parser reads', () => {
    const readme = readFileSync(join(import.meta.dirname, '../../README.md'), 'utf8')
    const parser = readFileSync(join(import.meta.dirname, 'config.ts'), 'utf8')
    const variables = [...new Set(parser.match(/BLACKHOLE_UI_EVIDENCE_[A-Z_]+/g))]
    const keys = [...new Set(parser.match(/max(?:Image|Video)Bytes|allowedTypes/g))].map((key) => `evidence.${key}`)

    expect(variables.length, 'variables found in the parser').toBe(3)
    expect(keys.length, 'keys found in the parser').toBe(3)
    for (const name of variables) expect(readme, name).toContain(`\`${name}\``)
    for (const key of keys) expect(readme, key).toContain(`\`${key}\``)
  })

  it('documents every sign-in variable the parser reads and none of the removed ones', () => {
    const readme = readFileSync(join(import.meta.dirname, '../../README.md'), 'utf8')
    const parser = readFileSync(join(import.meta.dirname, 'config.ts'), 'utf8')
    const variables = [...new Set(parser.match(/BLACKHOLE_UI_(?:OIDC_[A-Z_]+|API_SCOPE)/g))]

    expect(variables.length, 'sign-in variables found in the parser').toBe(3)
    for (const name of variables) expect(readme, name).toContain(`\`${name}\``)
    expect(parser, 'parser').not.toMatch(/BLACKHOLE_UI_(?:SOURCE|REVIEWER)/)
  })
})

describe('parseUiConfig panel origin', () => {
  it('passes a valid origin on to the browser config', () => {
    const config = parseUiConfig({ ...OIDC, BLACKHOLE_UI_PANEL_ORIGIN: 'https://panel.example.org' })

    expect(config.oidc.panelOrigin, 'panelOrigin').toBe('https://panel.example.org')
  })

  it('leaves the pin unset when no origin is configured', () => {
    expect('panelOrigin' in parseUiConfig(OIDC).oidc, 'panelOrigin present').toBe(false)
  })

  it.each(['https://panel.example.org/path', 'panel.example.org', 'http://panel.example.org', 'https://u:p@panel.example.org'])(
    'refuses %s as the panel origin',
    (value) => {
      expect(problemsOf({ ...OIDC, BLACKHOLE_UI_PANEL_ORIGIN: value }).join('\n')).toContain('BLACKHOLE_UI_PANEL_ORIGIN must be')
    },
  )
})

describe('parseUiConfig UI origin', () => {
  const SECURITY = { BLACKHOLE_SECURITY_CONTACT: 'mailto:security@onelitefeather.net', BLACKHOLE_SECURITY_EXPIRES: '2027-06-30T12:00:00Z' }

  it.each(['https://blackhole.onelitefeather.net', 'http://localhost:5173'])('accepts %s as the UI origin', (origin) => {
    expect(parseUiConfig({ ...OIDC, BLACKHOLE_UI_ORIGIN: origin }).uiOrigin).toBe(origin)
  })

  it.each(['https://blackhole.onelitefeather.net/', 'https://blackhole.onelitefeather.net/path', 'https://u:p@blackhole.example', 'blackhole.onelitefeather.net'])(
    'refuses %s as the UI origin',
    (origin) => {
      expect(problemsOf({ ...OIDC, BLACKHOLE_UI_ORIGIN: origin }).join('\n')).toContain('BLACKHOLE_UI_ORIGIN')
    },
  )

  it('refuses plain http for a real host outside production', () => {
    expect(problemsOf({ ...OIDC, BLACKHOLE_UI_ORIGIN: 'http://blackhole.example.org' }).join('\n')).toContain('BLACKHOLE_UI_ORIGIN')
  })

  it('refuses plain http for a loopback host in production', () => {
    expect(problemsOf({ ...OIDC, NODE_ENV: 'production', BLACKHOLE_UI_ORIGIN: 'http://localhost:5173' }).join('\n')).toContain('BLACKHOLE_UI_ORIGIN')
  })

  it('requires the UI origin when a security contact is configured', () => {
    expect(problemsOf({ ...OIDC, ...SECURITY }).join('\n')).toContain('BLACKHOLE_UI_ORIGIN')
  })

  it('carries the security.txt value with Canonical on the UI origin', () => {
    const config = parseUiConfig({ ...OIDC, ...SECURITY, BLACKHOLE_UI_ORIGIN: 'https://blackhole.onelitefeather.net' }, NOW)

    expect(config.securityTxt?.canonical).toBe('https://blackhole.onelitefeather.net/.well-known/security.txt')
  })

  it('keeps the security.txt value out of the browser config', () => {
    const config = parseUiConfig({ ...OIDC, ...SECURITY, BLACKHOLE_UI_ORIGIN: 'https://blackhole.onelitefeather.net' }, NOW)

    expect(toPublicConfig(config)).not.toHaveProperty('securityTxt')
  })

  it('reports security.txt problems with the other settings and names the variable', () => {
    const problems = problemsOf({ ...OIDC, BLACKHOLE_UI_ORIGIN: 'https://blackhole.onelitefeather.net', BLACKHOLE_SECURITY_CONTACT: 'mailto:security@onelitefeather.net' })

    expect(problems.join('\n')).toContain('BLACKHOLE_SECURITY_EXPIRES')
  })
})
