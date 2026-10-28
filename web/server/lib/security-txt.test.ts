// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { parseSecurityTxt, renderSecurityTxt, securityTxtResponse } from './security-txt'

const ORIGIN = 'https://appeal.onelitefeather.net'
const NOW = new Date('2026-10-28T12:00:00Z')
const VALID = { BLACKHOLE_SECURITY_CONTACT: 'mailto:security@onelitefeather.net', BLACKHOLE_SECURITY_EXPIRES: '2027-06-30T12:00:00Z' }

function parse(env: Record<string, string>) {
  return parseSecurityTxt(env, ORIGIN, NOW)
}

describe('parseSecurityTxt validation', () => {
  it.each([
    'security@onelitefeather.net',
    'http://onelitefeather.net/security',
    'ftp://onelitefeather.net',
  ])('refuses the contact %s, which is not mailto, tel or https', (contact) => {
    const { problems } = parse({ ...VALID, BLACKHOLE_SECURITY_CONTACT: contact })
    expect(problems.join('\n')).toContain('BLACKHOLE_SECURITY_CONTACT')
  })

  it('refuses an expiry without a time zone', () => {
    const { problems } = parse({ ...VALID, BLACKHOLE_SECURITY_EXPIRES: '2027-06-30T12:00:00' })
    expect(problems.join('\n')).toContain('BLACKHOLE_SECURITY_EXPIRES')
  })

  it('refuses an expiry that is not a timestamp at all', () => {
    const { problems } = parse({ ...VALID, BLACKHOLE_SECURITY_EXPIRES: 'next year' })
    expect(problems.join('\n')).toContain('BLACKHOLE_SECURITY_EXPIRES')
  })

  it('refuses an expiry in the past', () => {
    const { problems } = parse({ ...VALID, BLACKHOLE_SECURITY_EXPIRES: '2026-10-01T00:00:00Z' })
    expect(problems.join('\n')).toContain('BLACKHOLE_SECURITY_EXPIRES')
  })

  it('refuses an expiry more than one calendar year ahead', () => {
    const { problems } = parse({ ...VALID, BLACKHOLE_SECURITY_EXPIRES: '2027-10-29T00:00:00Z' })
    expect(problems.join('\n')).toContain('BLACKHOLE_SECURITY_EXPIRES')
  })

  it('accepts an expiry exactly one calendar year ahead', () => {
    const { problems } = parse({ ...VALID, BLACKHOLE_SECURITY_EXPIRES: '2027-10-28T12:00:00Z' })
    expect(problems).toEqual([])
  })

  it('refuses a contact without an expiry and names the expiry variable', () => {
    const { problems } = parse({ BLACKHOLE_SECURITY_CONTACT: VALID.BLACKHOLE_SECURITY_CONTACT })
    expect(problems.join('\n')).toContain('BLACKHOLE_SECURITY_EXPIRES')
  })

  it('refuses a non-https policy link and names its variable', () => {
    const { problems } = parse({ ...VALID, BLACKHOLE_SECURITY_POLICY: 'http://onelitefeather.net/policy' })
    expect(problems.join('\n')).toContain('BLACKHOLE_SECURITY_POLICY')
  })

  it('refuses a language tag that is not RFC 5646 shaped', () => {
    const { problems } = parse({ ...VALID, BLACKHOLE_SECURITY_PREFERRED_LANGUAGES: 'en, de german' })
    expect(problems.join('\n')).toContain('BLACKHOLE_SECURITY_PREFERRED_LANGUAGES')
  })
})

describe('parseSecurityTxt content', () => {
  it('accepts a valid block without problems', () => {
    expect(parse(VALID).problems).toEqual([])
  })

  it('derives Canonical from the portal origin', () => {
    expect(parse(VALID).value?.canonical).toBe('https://appeal.onelitefeather.net/.well-known/security.txt')
  })

  it('defaults Preferred-Languages to en, de', () => {
    expect(parse(VALID).value?.preferredLanguages).toBe('en, de')
  })

  it('emits Policy, Encryption and Acknowledgments only when set', () => {
    const body = renderSecurityTxt(parse(VALID).value!)
    expect(body).not.toContain('Policy:')
    expect(body).not.toContain('Encryption:')
    expect(body).not.toContain('Acknowledgments:')
  })

  it('renders the optional links when they are set', () => {
    const value = parse({
      ...VALID,
      BLACKHOLE_SECURITY_POLICY: 'https://onelitefeather.net/policy',
      BLACKHOLE_SECURITY_ACKNOWLEDGMENTS: 'https://onelitefeather.net/thanks',
      BLACKHOLE_SECURITY_ENCRYPTION: 'https://onelitefeather.net/pgp.txt',
    }).value!
    const body = renderSecurityTxt(value)
    expect(body).toContain('Policy: https://onelitefeather.net/policy\n')
    expect(body).toContain('Acknowledgments: https://onelitefeather.net/thanks\n')
    expect(body).toContain('Encryption: https://onelitefeather.net/pgp.txt\n')
  })

  it('renders Contact, Expires, Preferred-Languages and Canonical', () => {
    expect(renderSecurityTxt(parse(VALID).value!)).toBe(
      [
        'Contact: mailto:security@onelitefeather.net',
        'Expires: 2027-06-30T12:00:00Z',
        'Preferred-Languages: en, de',
        'Canonical: https://appeal.onelitefeather.net/.well-known/security.txt',
        '',
      ].join('\n'),
    )
  })

  it('reports no value and no problem when the contact is unset', () => {
    expect(parse({})).toEqual({ value: undefined, problems: [] })
  })
})

describe('securityTxtResponse', () => {
  it('answers 200 with the RFC 9116 content type and cache policy', () => {
    const response = securityTxtResponse(parse(VALID).value)
    expect(response.status, 'status').toBe(200)
    expect(response.headers['Content-Type'], 'content type').toBe('text/plain; charset=utf-8')
    expect(response.headers['Cache-Control'], 'cache control').toBe('public, max-age=86400')
  })

  it('answers 404 when the file is disabled', () => {
    expect(securityTxtResponse(undefined).status).toBe(404)
  })
})
