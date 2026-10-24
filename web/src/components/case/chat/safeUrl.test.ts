import { describe, expect, it } from 'vitest'
import { decodeLeaveTarget, encodeLeaveTarget, safeHttpUrl } from './safeUrl'

describe('safeHttpUrl', () => {
  it.each([
    'https://example.org/path?x=1',
    'http://example.org',
    'HTTPS://Example.ORG/Mixed',
    'https://example.org:8443/a',
    'https://xn--bcher-kva.example/',
  ])('accepts %s and returns it normalised', (value) => {
    expect(safeHttpUrl(value)).toBe(new URL(value).href)
  })

  it.each([
    ['javascript:alert(1)'],
    ['JaVaScRiPt:alert(1)'],
    ['data:text/html,<b>x</b>'],
    ['vbscript:msgbox'],
    ['file:///etc/passwd'],
    ['mailto:someone@example.org'],
    ['//example.org/path'],
    ['/relative/path'],
    ['example.org'],
    ['https://user:pass@example.org/'],
    ['https://example.org:0/'],
    ['https://example.org:70000/'],
    ['https://'],
    ['https://exa mple.org/'],
    ['https://example.org/\\evil'],
    ['https://example.org/<script>'],
    ['https://example.org/"quoted"'],
    ['https://example.org/\u0007'],
    ['https://example.org/' + 'a'.repeat(2048)],
    [''],
    ['   '],
  ])('refuses %j', (value) => {
    expect(safeHttpUrl(value)).toBeNull()
  })

  it('trims surrounding white space before it checks', () => {
    expect(safeHttpUrl('  https://example.org/ok  ')).toBe('https://example.org/ok')
  })
})

describe('the interstitial target', () => {
  it('round-trips a URL through the fragment encoding', () => {
    const url = 'https://example.org/ä?q=1&r=2'
    const encoded = encodeLeaveTarget(url)

    expect(encoded).toMatch(/^[A-Za-z0-9_-]+$/)
    expect(decodeLeaveTarget(encoded)).toBe(url)
  })

  it('decodes nothing it cannot read', () => {
    expect(decodeLeaveTarget('%%%')).toBeNull()
    expect(decodeLeaveTarget('')).toBeNull()
  })
})
