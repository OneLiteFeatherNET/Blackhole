// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ConfigError, parseUiConfig, toPublicConfig } from './config'

const SOURCE = '3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b'
const REVIEWER = '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d'

function problemsOf(env: Record<string, string>): string[] {
  try {
    parseUiConfig(env)
  } catch (error) {
    expect(error, 'a ConfigError is thrown').toBeInstanceOf(ConfigError)
    return (error as ConfigError).problems
  }
  throw new Error('expected parseUiConfig to throw')
}

describe('parseUiConfig', () => {
  it('uses the operator UUID as the reviewer by default', () => {
    const config = parseUiConfig({ BLACKHOLE_UI_SOURCE: SOURCE })

    expect(config, 'source and reviewer').toMatchObject({ source: SOURCE, reviewer: SOURCE })
  })

  it('takes a separate reviewer when one is given', () => {
    const config = parseUiConfig({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_UI_REVIEWER: REVIEWER })

    expect(config, 'source and reviewer').toMatchObject({ source: SOURCE, reviewer: REVIEWER })
  })

  it('refuses to start without an operator UUID', () => {
    expect(problemsOf({}).join('\n')).toContain('BLACKHOLE_UI_SOURCE must be a UUID')
  })

  it('refuses a value that is not a UUID, so nothing can be injected into the JSON', () => {
    expect(problemsOf({ BLACKHOLE_UI_SOURCE: `${SOURCE}","x":"y` }).join('\n')).toContain('must be a UUID')
  })

  it('refuses an invalid reviewer', () => {
    expect(problemsOf({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_UI_REVIEWER: 'nobody' }).join('\n')).toContain(
      'BLACKHOLE_UI_REVIEWER must be a UUID',
    )
  })

  it('reads the evidence upload limits when they are set', () => {
    const config = parseUiConfig({
      BLACKHOLE_UI_SOURCE: SOURCE,
      BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES: '2048',
      BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES: '4096',
      BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES: 'image/png,video/mp4',
    })

    expect(config.evidence).toEqual({ maxImageBytes: 2048, maxVideoBytes: 4096, allowedTypes: ['image/png', 'video/mp4'] })
  })

  it('leaves evidence out when no limit is set', () => {
    expect(parseUiConfig({ BLACKHOLE_UI_SOURCE: SOURCE }).evidence, 'evidence').toBeUndefined()
  })

  it.each(['0', '-1', '1.5', '01', '1,"x":"y', 'abc', '12345678901234567'])('refuses the evidence limit %j', (value) => {
    expect(problemsOf({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES: value }).join('\n')).toContain(
      'BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES must be a positive number',
    )
  })

  it('refuses allowed types that are not MIME types', () => {
    expect(problemsOf({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES: 'image/png","x":"y' }).join('\n')).toContain(
      'BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES must be comma separated MIME types',
    )
  })

  it('defaults the backend URL to http://backend:8080', () => {
    expect(parseUiConfig({ BLACKHOLE_UI_SOURCE: SOURCE }).backendUrl).toBe('http://backend:8080')
  })

  it('strips trailing slashes from the backend URL', () => {
    expect(parseUiConfig({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_BACKEND_URL: 'http://other:9000//' }).backendUrl).toBe('http://other:9000')
  })

  it('accepts an https backend URL with a path prefix', () => {
    expect(parseUiConfig({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_BACKEND_URL: 'https://api.example.org/base/' }).backendUrl).toBe(
      'https://api.example.org/base',
    )
  })

  it.each(['ftp://backend', 'backend:8080', 'not a url', 'http://user:pw@backend', 'http://backend?x=1', 'http://backend#frag', 'file:///etc/passwd'])(
    'refuses the backend URL %j',
    (value) => {
      expect(problemsOf({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_BACKEND_URL: value }).join('\n')).toContain('BLACKHOLE_BACKEND_URL must be')
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
      'BLACKHOLE_UI_SOURCE',
      'BLACKHOLE_UI_REVIEWER',
      'BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES',
      'BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES',
      'BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES',
    ]) {
      expect(problems.some((p) => p.startsWith(name)), `${name} is named`).toBe(true)
    }
  })

  it('reports the missing source once for source and reviewer when the reviewer is unset', () => {
    expect(problemsOf({})).toHaveLength(2)
  })
})

describe('toPublicConfig', () => {
  it('does not expose the backend URL', () => {
    const config = parseUiConfig({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_BACKEND_URL: 'http://internal:8080' })

    expect(toPublicConfig(config)).toEqual({ source: SOURCE, reviewer: SOURCE })
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
})
