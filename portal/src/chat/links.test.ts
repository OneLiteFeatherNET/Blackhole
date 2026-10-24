import { describe, expect, it } from 'vitest'
import { isTrustedHost, isTrustedUrl, leavePath, leaveTarget, safeHttpUrl } from './links'

describe('isTrustedHost', () => {
  it.each(['onelitefeather.net', 'appeal.onelitefeather.net', 'blackhole.onelitefeather.net', 'ONELITEFEATHER.NET', 'onelitefeather.net.'])(
    'trusts %s',
    (host) => {
      expect(isTrustedHost(host)).toBe(true)
    },
  )

  it.each([
    ['evilonelitefeather.net', 'a suffix match without a dot'],
    ['onelitefeather.net.evil.example', 'the domain as a label in the middle'],
    ['evil.example', 'another domain'],
    ['xonelitefeather.net', 'a letter before the domain'],
    ['.onelitefeather.net', 'an empty label'],
  ])('does not trust %s (%s)', (host) => {
    expect(isTrustedHost(host)).toBe(false)
  })
})

describe('safeHttpUrl', () => {
  it('keeps a plain https link, normalised', () => {
    expect(safeHttpUrl('https://Example.org/a?b=1')).toBe('https://example.org/a?b=1')
  })

  it('keeps http as well', () => {
    expect(safeHttpUrl('http://example.org')).toBe('http://example.org/')
  })

  it.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'java\tscript:alert(1)',
    'data:text/html;base64,PHNjcmlwdD4=',
    'vbscript:msgbox',
    'file:///etc/passwd',
    'mailto:someone@example.org',
    '//evil.example/path',
    '/relative/path',
    'https://user:pass@example.org/',
    'https://example.org/with space',
    'https://example.org/quote"',
    'https://example.org/<b>',
    'https://example.org/back\\slash',
    'https://example.org/\u0007',
    'https://example.org:0/',
    '',
    '   ',
  ])('refuses %j', (url) => {
    expect(safeHttpUrl(url)).toBeNull()
  })

  it('refuses a URL longer than 2048 characters', () => {
    expect(safeHttpUrl(`https://example.org/${'a'.repeat(2048)}`)).toBeNull()
  })

  it('refuses anything that is not text', () => {
    expect(safeHttpUrl(42)).toBeNull()
    expect(safeHttpUrl(undefined)).toBeNull()
    expect(safeHttpUrl({ href: 'https://example.org' })).toBeNull()
  })
})

describe('isTrustedUrl', () => {
  it('trusts the portal domain and its subdomains', () => {
    expect(isTrustedUrl('https://appeal.onelitefeather.net/case')).toBe(true)
  })

  it.each(['https://evilonelitefeather.net/', 'https://onelitefeather.net.evil.example/', 'https://onelitefeather.net@evil.example/', 'javascript:alert(1)'])(
    'does not trust %s',
    (url) => {
      expect(isTrustedUrl(url)).toBe(false)
    },
  )
})

describe('leave path', () => {
  it('keeps the target in the fragment only', () => {
    const path = leavePath('https://example.org/x?y=1')

    expect(path.startsWith('/leave#')).toBe(true)
    expect(path).not.toContain('example.org')
    expect(path).not.toContain('?')
  })

  // The page reads the fragment (`window.location.hash`), so the tests pass the fragment, not the path.
  const fragmentOf = (path: string) => path.slice('/leave'.length)

  it('gives back exactly the target it was built from', () => {
    const url = 'https://example.org/ünïcode/path?q=ä'

    expect(leaveTarget(fragmentOf(leavePath(url)))).toBe(safeHttpUrl(url))
  })

  it('validates the target again when it is read', () => {
    const fragment = fragmentOf(leavePath('javascript:alert(1)'))

    expect(leaveTarget(fragment)).toBeNull()
  })

  it.each(['#', '#!!!', '#%%%', '#aGVsbG8', '#e30=', '#', '#=='])('answers null for the fragment %s', (fragment) => {
    expect(leaveTarget(fragment)).toBeNull()
  })

  it('accepts a fragment without the hash sign', () => {
    expect(leaveTarget(fragmentOf(leavePath('https://example.org/')).slice(1))).toBe('https://example.org/')
  })

  it('refuses bytes that are not valid UTF-8', () => {
    // 0xff is never valid UTF-8; base64url of [0xff, 0xfe] is "__4".
    expect(leaveTarget('#__4')).toBeNull()
  })
})
