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

  it('sends exactly the content security policy nginx used', () => {
    expect(securityHeaders()['Content-Security-Policy']).toBe(
      "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
    )
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
