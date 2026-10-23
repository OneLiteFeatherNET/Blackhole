// @vitest-environment node
import { createApp, eventHandler, toWebHandler, type EventHandler } from 'h3'
import { describe, expect, it } from 'vitest'
import { parseUiConfig, type UiConfig } from '../lib/config'
import {
  applySecurityHeaders,
  configJsonHandler,
  createApiProxyHandler,
  createSpaFallbackHandler,
  healthzHandler,
  sendErrorResponse,
} from '../lib/handlers'

const AUTHORITY = 'https://login.example.org/tenant/v2.0'
const OIDC = {
  BLACKHOLE_UI_OIDC_AUTHORITY: AUTHORITY,
  BLACKHOLE_UI_OIDC_CLIENT_ID: 'spa-client',
  BLACKHOLE_UI_API_SCOPE: 'api://x/access_as_user',
}
const INDEX_HTML = '<!doctype html><title>Blackhole</title>'

function config(env: Record<string, string> = {}): UiConfig {
  return parseUiConfig({ ...OIDC, ...env })
}

/** A fresh in-process app per test: the hook that the Nitro plugins register, then one handler. */
function appWith(handler: EventHandler, uiConfig: UiConfig = config()) {
  const app = createApp({ onError: (error, event) => sendErrorResponse(error, event) })
  app.use(
    eventHandler((event) => {
      event.context.uiConfig = uiConfig
      applySecurityHeaders(event)
    }),
  )
  app.use(handler)
  const web = toWebHandler(app)
  return (path: string, init?: RequestInit) => web(new Request(`http://ui.test${path}`, init))
}

const SECURITY_HEADERS = ['x-content-type-options', 'x-frame-options', 'referrer-policy', 'content-security-policy']

describe('GET /healthz', () => {
  it('answers 200 with body ok', async () => {
    const response = await appWith(healthzHandler)('/healthz')

    expect(response.status).toBe(200)
    expect(await response.text()).toBe('ok')
    expect(response.headers.get('content-type')).toContain('text/plain')
  })
})

describe('GET /config.json', () => {
  it('returns the configuration as JSON without the backend URL', async () => {
    const response = await appWith(configJsonHandler, config({ BLACKHOLE_BACKEND_URL: 'http://internal:8080' }))('/config.json')

    expect(response.headers.get('content-type')).toContain('application/json')
    expect(await response.json()).toEqual({ oidc: { authority: AUTHORITY, clientId: 'spa-client', apiScope: 'api://x/access_as_user' } })
  })

  it('is never cached', async () => {
    const response = await appWith(configJsonHandler)('/config.json')

    expect(response.headers.get('cache-control')).toBe('no-store')
  })

  it('includes the evidence limits', async () => {
    const response = await appWith(
      configJsonHandler,
      config({ BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES: '2048', BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES: 'image/png,video/mp4' }),
    )('/config.json')

    expect(((await response.json()) as UiConfig).evidence).toEqual({ maxImageBytes: 2048, allowedTypes: ['image/png', 'video/mp4'] })
  })
})

describe('security headers', () => {
  it('allow connecting to the configured authority origin', async () => {
    const response = await appWith(healthzHandler)('/healthz')

    expect(response.headers.get('content-security-policy')).toContain("connect-src 'self' https://login.example.org;")
  })

  it('allow connecting to the authority origin on error responses too', async () => {
    const response = await appWith(
      eventHandler(() => {
        throw new Error('boom')
      }),
    )('/x')

    expect(response.headers.get('content-security-policy')).toContain('https://login.example.org')
  })

  it.each([
    ['healthz', healthzHandler, '/healthz'],
    ['config.json', configJsonHandler, '/config.json'],
    ['the SPA fallback', createSpaFallbackHandler(async () => INDEX_HTML), '/players/abc'],
  ])('are set on %s', async (_label, handler, path) => {
    const response = await appWith(handler)(path)

    for (const name of SECURITY_HEADERS) expect(response.headers.has(name), name).toBe(true)
  })

  it('are set on error responses', async () => {
    const response = await appWith(
      eventHandler(() => {
        throw new Error('boom')
      }),
    )('/x')

    expect(response.status).toBe(500)
    for (const name of SECURITY_HEADERS) expect(response.headers.has(name), name).toBe(true)
  })

  it('do not leak the error message of an unexpected failure', async () => {
    const response = await appWith(
      eventHandler(() => {
        throw new Error('secret internals')
      }),
    )('/x')

    expect(await response.text()).not.toContain('secret internals')
  })

  it('leave out Server and X-Powered-By', async () => {
    const response = await appWith(healthzHandler)('/healthz')

    expect(response.headers.has('server'), 'server').toBe(false)
    expect(response.headers.has('x-powered-by'), 'x-powered-by').toBe(false)
  })

  it('remove an X-Powered-By header set earlier', async () => {
    const app = createApp()
    app.use(
      eventHandler((event) => {
        event.node.res.setHeader('X-Powered-By', 'something')
        applySecurityHeaders(event)
      }),
    )
    app.use(healthzHandler)

    const response = await toWebHandler(app)(new Request('http://ui.test/healthz'))

    expect(response.headers.has('x-powered-by')).toBe(false)
  })

  it('require revalidation for the index document', async () => {
    const response = await appWith(healthzHandler)('/index.html')

    expect(response.headers.get('cache-control')).toBe('no-cache')
  })
})

describe('SPA fallback', () => {
  const fallback = createSpaFallbackHandler(async () => INDEX_HTML)

  it('serves index.html with status 200 for a deep link', async () => {
    const response = await appWith(fallback)('/players/abc123')

    expect(response.status).toBe(200)
    expect(await response.text()).toBe(INDEX_HTML)
    expect(response.headers.get('content-type')).toContain('text/html')
  })

  it('revalidates index.html on every load', async () => {
    const response = await appWith(fallback)('/players/abc123')

    expect(response.headers.get('cache-control')).toBe('no-cache')
  })

  it('answers HEAD without a body', async () => {
    const response = await appWith(fallback)('/players/abc123', { method: 'HEAD' })

    expect(response.status).toBe(200)
    expect(await response.text()).toBe('')
  })

  it('answers POST with 404', async () => {
    const response = await appWith(fallback)('/players/abc123', { method: 'POST', body: 'x' })

    expect(response.status).toBe(404)
  })

  it('answers 404 when index.html is not bundled', async () => {
    const response = await appWith(createSpaFallbackHandler(async () => null))('/players/abc123')

    expect(response.status).toBe(404)
  })
})

describe('API proxy handler', () => {
  function proxied(respond: () => Response = () => new Response('{"ok":true}', { headers: { 'content-type': 'application/json' } })) {
    const calls: { url: string; init: RequestInit }[] = []
    const fetchImpl = ((url: URL, init: RequestInit) => {
      calls.push({ url: url.href, init })
      return Promise.resolve(respond())
    }) as unknown as typeof fetch
    return { calls, call: appWith(createApiProxyHandler(fetchImpl), config({ BLACKHOLE_BACKEND_URL: 'http://other:9000' })) }
  }

  it('forwards /api/x to the runtime backend URL', async () => {
    const { calls, call } = proxied()

    const response = await call('/api/player/resolve/Steve?x=1')

    expect(calls.map((c) => c.url)).toEqual(['http://other:9000/player/resolve/Steve?x=1'])
    expect(await response.json()).toEqual({ ok: true })
  })

  it('answers with no-store and the security headers', async () => {
    const { call } = proxied()

    const response = await call('/api/x')

    expect(response.headers.get('cache-control')).toBe('no-store')
    for (const name of SECURITY_HEADERS) expect(response.headers.has(name), name).toBe(true)
  })

  it('does not let the backend replace the security headers', async () => {
    const { call } = proxied(() => new Response('x', { headers: { 'x-frame-options': 'SAMEORIGIN', server: 'Netty' } }))

    const response = await call('/api/x')

    expect(response.headers.get('x-frame-options')).toBe('DENY')
    expect(response.headers.has('server')).toBe(false)
  })

  it('streams a POST body to the backend', async () => {
    const { calls, call } = proxied()

    await call('/api/evidence/file', { method: 'POST', body: 'file-bytes', headers: { 'content-type': 'application/octet-stream' } })

    expect(calls).toHaveLength(1)
    expect(calls[0].init.method).toBe('POST')
    expect(await new Response(calls[0].init.body as ReadableStream).text()).toBe('file-bytes')
  })

  it('answers 400 for a hostile path without calling the backend', async () => {
    const { calls, call } = proxied()

    const response = await call('/api/%2e%2e/admin')

    expect(response.status).toBe(400)
    expect(calls).toHaveLength(0)
  })

  it('answers 502 when the backend cannot be reached', async () => {
    const app = appWith(
      createApiProxyHandler((() => Promise.reject(new TypeError('fetch failed'))) as unknown as typeof fetch),
    )

    expect((await app('/api/x')).status).toBe(502)
  })
})
