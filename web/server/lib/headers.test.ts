// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { isIndexDocument, securityHeaders } from './headers'

describe('securityHeaders', () => {
  it('forbids content type sniffing', () => {
    expect(securityHeaders()['X-Content-Type-Options']).toBe('nosniff')
  })

  it('forbids framing', () => {
    expect(securityHeaders()['X-Frame-Options']).toBe('DENY')
  })

  it('sends no referrer', () => {
    expect(securityHeaders()['Referrer-Policy']).toBe('no-referrer')
  })

  it('sends the content security policy, connecting to the sign-in authority only', () => {
    expect(securityHeaders('https://login.microsoftonline.com/tenant/v2.0')['Content-Security-Policy']).toBe(
      "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' https://login.microsoftonline.com; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
    )
  })

  it('allows the port of a local authority and nothing else of its URL', () => {
    expect(securityHeaders('http://localhost:18090/blackhole')['Content-Security-Policy']).toContain("connect-src 'self' http://localhost:18090;")
  })

  it('never uses a wildcard source', () => {
    expect(securityHeaders('https://login.microsoftonline.com/t/v2.0')['Content-Security-Policy']).not.toContain('*')
  })

  it('connects to its own origin only when no authority is known', () => {
    expect(securityHeaders()['Content-Security-Policy']).toContain("connect-src 'self';")
  })

  it('sets nothing that discloses the server software', () => {
    const names = Object.keys(securityHeaders()).map((n) => n.toLowerCase())

    expect(names).not.toContain('server')
    expect(names).not.toContain('x-powered-by')
  })

  it('returns a fresh object on every call', () => {
    const first = securityHeaders()
    first['X-Frame-Options'] = 'changed'

    expect(securityHeaders()['X-Frame-Options']).toBe('DENY')
  })
})

describe('isIndexDocument', () => {
  it.each(['/', '/index.html', '/?x=1', '/index.html?v=2'])('treats %s as the index document', (path) => {
    expect(isIndexDocument(path)).toBe(true)
  })

  it.each(['/players/abc', '/assets/index.js', '/config.json', '/api/'])('does not treat %s as the index document', (path) => {
    expect(isIndexDocument(path)).toBe(false)
  })
})
