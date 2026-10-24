// @vitest-environment node
import { createApp, eventHandler, toWebHandler } from 'h3'
import { describe, expect, it, vi } from 'vitest'
import { applySecurityHeaders, healthzHandler, sendErrorResponse } from './handlers'
import { isIndexDocument, securityHeaders } from './headers'

describe('securityHeaders', () => {
  it('forbids content type sniffing', () => {
    expect(securityHeaders()['X-Content-Type-Options']).toBe('nosniff')
  })

  it('forbids framing, in the header and in the policy', () => {
    expect(securityHeaders()['X-Frame-Options']).toBe('DENY')
    expect(securityHeaders()['Content-Security-Policy']).toContain("frame-ancestors 'none'")
  })

  it('sends no referrer', () => {
    expect(securityHeaders()['Referrer-Policy']).toBe('no-referrer')
  })

  it('allows only its own origin, with no wildcard and no inline script or style', () => {
    const csp = securityHeaders()['Content-Security-Policy']!

    expect(csp).toContain("default-src 'self'")
    expect(csp).toContain("connect-src 'self'")
    expect(csp).not.toContain('*')
    expect(csp).not.toContain('unsafe-inline')
    expect(csp).not.toContain('unsafe-eval')
  })

  it('returns a fresh object on every call', () => {
    const first = securityHeaders()
    first['X-Frame-Options'] = 'changed'

    expect(securityHeaders()['X-Frame-Options']).toBe('DENY')
  })
})

describe('isIndexDocument', () => {
  it.each(['/', '/index.html', '/?x=1'])('treats %s as the index document', (path) => {
    expect(isIndexDocument(path)).toBe(true)
  })

  it.each(['/assets/index.js', '/api/session'])('does not treat %s as the index document', (path) => {
    expect(isIndexDocument(path)).toBe(false)
  })
})

function appWith(handler: ReturnType<typeof eventHandler>) {
  const app = createApp({ onError: (error, event) => sendErrorResponse(error, event) })
  app.use(eventHandler((event) => applySecurityHeaders(event)))
  app.use(handler)
  const web = toWebHandler(app)
  return (path: string, init?: RequestInit) => web(new Request(`http://portal.test${path}`, init))
}

describe('handlers', () => {
  it('answers /healthz with ok', async () => {
    const response = await appWith(healthzHandler)('/healthz')

    expect(response.status).toBe(200)
    expect(await response.text()).toBe('ok')
  })

  it('sets the security headers and discloses no software', async () => {
    const response = await appWith(healthzHandler)('/healthz')

    expect(response.headers.get('content-security-policy')).toContain("default-src 'self'")
    expect(response.headers.get('server')).toBeNull()
    expect(response.headers.get('x-powered-by')).toBeNull()
  })

  it('answers an unexpected error as a plain 500 without internals and logs method and path only', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const boom = eventHandler(() => {
      throw new Error('secret K7M29QX4 in the body')
    })

    const response = await appWith(boom)('/boom?banId=K7M29QX4', { method: 'POST', body: '{"banId":"K7M29QX4"}' })

    expect(response.status).toBe(500)
    expect(await response.text()).toBe('500 Server Error')
    expect(response.headers.get('cache-control')).toBe('no-store')
    const logged = log.mock.calls.flat().map(String).join('\n')
    expect(logged).toContain('[POST] /boom')
    expect(logged).not.toContain('K7M29QX4')
    log.mockRestore()
  })
})
