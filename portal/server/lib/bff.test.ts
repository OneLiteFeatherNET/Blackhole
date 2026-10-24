// @vitest-environment node
import { createApp, eventHandler, toWebHandler } from 'h3'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CSRF_COOKIE, ROUTES, SESSION_COOKIE } from '../../src/api/contract'
import { createBffHandler, mapBackendError } from './bff'
import { parsePortalConfig } from './config'
import { applySecurityHeaders, sendErrorResponse } from './handlers'

const ORIGIN = 'https://appeal.example.org'
const BAN_ID = 'K7M29QX4'
const TOKEN = 'tok_0123456789abcdefghijklmnopqrstuvwxyz'
const CSRF = 'csrf_0123456789abcdef'

interface Call {
  url: string
  method: string
  headers: Headers
  body: string
}

type Reply = { status?: number; body?: unknown; headers?: Record<string, string>; text?: string } | Error

/** A fake backend: records what the BFF sends and answers from a queue. No network, no timers. */
function fakeBackend(replies: Reply[] = []) {
  const calls: Call[] = []
  const impl = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method ?? 'GET',
      headers: new Headers(init?.headers),
      body: typeof init?.body === 'string' ? init.body : '',
    })
    const next = replies.shift() ?? { status: 200, body: {} }
    if (next instanceof Error) throw next
    return new Response(next.text ?? (next.body === undefined ? null : JSON.stringify(next.body)), {
      status: next.status ?? 200,
      headers: { 'content-type': 'application/json', ...next.headers },
    })
  }) as unknown as typeof fetch
  return { calls, impl }
}

function bff(replies: Reply[] = []) {
  const backend = fakeBackend(replies)
  const config = parsePortalConfig({ NODE_ENV: 'production', BLACKHOLE_PORTAL_ORIGIN: ORIGIN, BLACKHOLE_BACKEND_URL: 'http://backend.test:8080' })
  const app = createApp({ onError: (error, event) => sendErrorResponse(error, event) })
  app.use(eventHandler((event) => applySecurityHeaders(event)))
  app.use(createBffHandler({ config, fetchImpl: backend.impl }))
  const web = toWebHandler(app)
  return { calls: backend.calls, send: (path: string, init?: RequestInit) => web(new Request(`http://portal.test${path}`, init)) }
}

const session = `${SESSION_COOKIE}=${TOKEN}; ${CSRF_COOKIE}=${CSRF}`

function post(path: string, body: unknown, headers: Record<string, string> = {}): [string, RequestInit] {
  return [path, { method: 'POST', body: JSON.stringify(body), headers: { 'content-type': 'application/json', origin: ORIGIN, ...headers } }]
}

const LOGIN_OK = { token: TOKEN, csrf: CSRF, expiresAt: '2099-01-01T00:00:00Z', scope: 'FULL' }

afterEach(() => vi.restoreAllMocks())

describe('allow-list', () => {
  it.each(['/api/case', '/api/punishment/lookup', '/api/', '/api', '/api/session/extra', '/api/%2e%2e/case', '/api/public/session', '/api//session'])(
    'answers 404 for %s without calling the backend',
    async (path) => {
      const { send, calls } = bff()

      const response = await send(path, { headers: { cookie: session } })

      expect(response.status).toBe(404)
      expect(calls, 'no backend call').toEqual([])
    },
  )

  it('answers 404 for a known path with another method', async () => {
    const { send, calls } = bff()

    const response = await send('/api/punishment', { method: 'POST', headers: { origin: ORIGIN, cookie: session } })

    expect(response.status).toBe(404)
    expect(calls).toEqual([])
  })

  it('maps every allowed route one to one onto /public', async () => {
    for (const route of ROUTES) {
      const { send, calls } = bff([{ body: route.path === '/api/session' && route.method === 'POST' ? LOGIN_OK : {} }])
      const path = route.path.replace(':id', 'abc123')
      const init: RequestInit = route.upload
        ? { method: 'POST', headers: { origin: ORIGIN, 'x-csrf-token': CSRF, cookie: session, 'content-type': 'multipart/form-data; boundary=x', 'content-length': '4' }, body: 'test' }
        : route.method === 'GET'
          ? { headers: { cookie: session } }
          : { method: route.method, headers: { origin: ORIGIN, 'x-csrf-token': CSRF, cookie: session, 'content-type': 'application/json' }, body: JSON.stringify({ banId: BAN_ID, statement: 's', text: 't', conditionsVersion: 1 }) }

      await send(path, init)

      expect(calls[0]?.url, `${route.method} ${route.path}`).toBe(`http://backend.test:8080${route.backend.replace(':id', 'abc123')}`)
      expect(calls[0]?.method).toBe(route.method)
    }
  })

  it('forwards only the cursor of the conversation query', async () => {
    const { send, calls } = bff([{ body: { entries: [], cursor: null } }])

    await send('/api/conversation?cursor=42&punishment=other&appealId=7', { headers: { cookie: session } })

    expect(calls[0]?.url).toBe('http://backend.test:8080/public/conversation?cursor=42')
  })

  it.each(['/api/conversation?cursor=abc', '/api/conversation?cursor=1&cursor=2', '/api/appeal/protocol?format=xml'])('rejects the query of %s', async (path) => {
    const { send, calls } = bff()

    const response = await send(path, { headers: { cookie: session } })

    expect(response.status).toBe(400)
    expect(calls).toEqual([])
  })

  it('forwards only the whitelisted fields of a body', async () => {
    const { send, calls } = bff([{ body: {} }])

    await send(...post('/api/messages', { text: 'hello', punishment: 'other', visibility: 'STAFF' }, { 'x-csrf-token': CSRF, cookie: session }))

    expect(JSON.parse(calls[0]!.body)).toEqual({ text: 'hello' })
  })

  it('never forwards the browser cookies and attaches the token server-side', async () => {
    const { send, calls } = bff([{ body: {} }])

    await send('/api/punishment', { headers: { cookie: `${session}; other=1`, authorization: 'Bearer attacker', 'x-forwarded-for': '203.0.113.9' } })

    expect(calls[0]?.headers.get('cookie')).toBeNull()
    expect(calls[0]?.headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
  })

  it('forwards a well-formed idempotency key and drops a malformed one', async () => {
    const good = bff([{ body: {} }])
    await good.send(...post('/api/messages', { text: 'a' }, { 'x-csrf-token': CSRF, cookie: session, 'idempotency-key': '3f2b8c1e-aaaa-bbbb-cccc-0123456789ab' }))
    const bad = bff([{ body: {} }])
    await bad.send(...post('/api/messages', { text: 'a' }, { 'x-csrf-token': CSRF, cookie: session, 'idempotency-key': 'x y;z' }))

    expect(good.calls[0]?.headers.get('idempotency-key')).toBe('3f2b8c1e-aaaa-bbbb-cccc-0123456789ab')
    expect(bad.calls[0]?.headers.get('idempotency-key')).toBeNull()
  })
})

describe('Ban ID handling', () => {
  it('sends the Ban ID in the POST body only, never in the URL', async () => {
    const { send, calls } = bff([{ body: LOGIN_OK }])

    await send(...post('/api/session', { banId: BAN_ID }))

    expect(calls[0]?.url).not.toContain(BAN_ID)
    expect(calls[0]?.url).not.toContain('?')
    expect(JSON.parse(calls[0]!.body)).toEqual({ banId: BAN_ID })
  })

  it('refuses a Ban ID in the query string of the login', async () => {
    const { send, calls } = bff([{ body: LOGIN_OK }])

    await send(`/api/session?banId=${BAN_ID}`, { method: 'POST', body: JSON.stringify({ banId: BAN_ID }), headers: { origin: ORIGIN, 'content-type': 'application/json' } })

    expect(calls[0]?.url, 'dropped, not forwarded').not.toContain(BAN_ID)
  })

  it('writes the Ban ID to no log, on success or on failure', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined))
    const ok = bff([{ body: LOGIN_OK }])
    await ok.send(...post('/api/session', { banId: BAN_ID }))
    const bad = bff([{ status: 401, body: { detail: BAN_ID } }])
    await bad.send(...post('/api/session', { banId: BAN_ID }))
    const down = bff([new Error(`connect ECONNREFUSED ${BAN_ID}`)])
    await down.send(...post('/api/session', { banId: BAN_ID }))
    const malformed = bff()
    await malformed.send('/api/session', { method: 'POST', body: `{"banId":"${BAN_ID}`, headers: { origin: ORIGIN } })

    const logged = spies.flatMap((spy) => spy.mock.calls).flat().map(String).join('\n')
    expect(logged).not.toContain(BAN_ID)
  })

  it('never echoes the Ban ID in a response', async () => {
    const bad = bff([{ status: 401, body: { message: `no such ${BAN_ID}` } }])

    const response = await bad.send(...post('/api/session', { banId: BAN_ID }))

    expect(await response.text()).not.toContain(BAN_ID)
  })
})

describe('login', () => {
  it('sets both cookies with the required flags', async () => {
    const { send } = bff([{ body: LOGIN_OK }])

    const response = await send(...post('/api/session', { banId: BAN_ID }))

    const cookies = response.headers.getSetCookie()
    const sessionCookie = cookies.find((c) => c.startsWith(`${SESSION_COOKIE}=`))!
    const csrfCookie = cookies.find((c) => c.startsWith(`${CSRF_COOKIE}=`))!
    for (const cookie of [sessionCookie, csrfCookie]) {
      expect(cookie).toContain('HttpOnly')
      expect(cookie).toContain('Secure')
      expect(cookie).toContain('SameSite=Lax')
      expect(cookie).toContain('Path=/')
      expect(cookie.toLowerCase(), 'a __Host- cookie has no Domain').not.toContain('domain=')
      expect(cookie).toMatch(/Max-Age=\d+/)
    }
    expect(sessionCookie).toContain(`${SESSION_COOKIE}=${TOKEN}`)
  })

  it('caps the cookie lifetime at the absolute session limit', async () => {
    const { send } = bff([{ body: { ...LOGIN_OK, expiresAt: '2099-01-01T00:00:00Z' } }])

    const response = await send(...post('/api/session', { banId: BAN_ID }))

    expect(response.headers.getSetCookie()[0]).toContain('Max-Age=14400')
  })

  it('keeps the token out of the response body', async () => {
    const { send } = bff([{ body: LOGIN_OK }])

    const response = await send(...post('/api/session', { banId: BAN_ID }))

    const text = await response.text()
    expect(text).not.toContain(TOKEN)
    expect(JSON.parse(text)).toEqual({ csrf: CSRF, expiresAt: LOGIN_OK.expiresAt, scope: 'FULL' })
  })

  it('answers every failure with the same status and the same body', async () => {
    const answers: Array<{ status: number; body: string }> = []
    for (const reply of [{ status: 401 }, { status: 400, body: { detail: 'bad length' } }, { status: 404 }, { status: 422, body: { x: 1 } }, { status: 401, body: { code: 'INVALID_BAN_ID' } }] as Reply[]) {
      const { send } = bff([reply])
      const response = await send(...post('/api/session', { banId: BAN_ID }))
      answers.push({ status: response.status, body: await response.text() })
    }

    expect(new Set(answers.map((a) => a.status))).toEqual(new Set([401]))
    expect(new Set(answers.map((a) => a.body)).size, 'byte-identical bodies').toBe(1)
    expect(JSON.parse(answers[0]!.body)).toEqual({ code: 'INVALID_BAN_ID' })
  })

  it('sets no cookie on a failed login', async () => {
    const { send } = bff([{ status: 401 }])

    const response = await send(...post('/api/session', { banId: BAN_ID }))

    expect(response.headers.getSetCookie()).toEqual([])
  })

  it('reports an unreachable backend as unavailable, not as a wrong Ban ID', async () => {
    const { send } = bff([new Error('down')])

    const response = await send(...post('/api/session', { banId: BAN_ID }))

    expect(response.status).toBe(503)
    expect(await response.json()).toEqual({ code: 'UNAVAILABLE' })
  })

  it('refuses a backend answer without a usable token', async () => {
    const { send } = bff([{ body: { ...LOGIN_OK, token: 'a b;c' } }])

    const response = await send(...post('/api/session', { banId: BAN_ID }))

    expect(response.status).toBe(503)
    expect(response.headers.getSetCookie()).toEqual([])
  })

  it('rejects a body that is not JSON with the right shape', async () => {
    for (const body of ['not json', '[]', '{"banId":5}', '{}']) {
      const { send, calls } = bff()

      const response = await send('/api/session', { method: 'POST', body, headers: { origin: ORIGIN } })

      expect(response.status, body).toBe(400)
      expect(calls).toEqual([])
    }
  })

  it('rejects a body above 16 KiB', async () => {
    const { send, calls } = bff()

    const response = await send(...post('/api/session', { banId: 'A'.repeat(17 * 1024) }))

    expect(response.status).toBe(413)
    expect(calls).toEqual([])
  })
})

describe('CSRF and Origin', () => {
  it.each([
    ['POST /api/session', '/api/session', 'POST', { banId: BAN_ID }],
    ['POST /api/appeal', '/api/appeal', 'POST', { statement: 'text' }],
    ['POST /api/messages', '/api/messages', 'POST', { text: 'text' }],
    ['DELETE /api/session', '/api/session', 'DELETE', undefined],
  ] as const)('%s with a foreign Origin answers 403 before the backend is called', async (_name, path, method, body) => {
    const { send, calls } = bff()

    const response = await send(path, { method, body: body && JSON.stringify(body), headers: { origin: 'https://evil.example', 'x-csrf-token': CSRF, cookie: session } })

    expect(response.status).toBe(403)
    expect(await response.json()).toEqual({ code: 'ORIGIN' })
    expect(calls).toEqual([])
  })

  it('answers 403 when the Origin header is missing', async () => {
    const { send, calls } = bff()

    const response = await send('/api/session', { method: 'POST', body: JSON.stringify({ banId: BAN_ID }) })

    expect(response.status).toBe(403)
    expect(calls).toEqual([])
  })

  it('rejects an Origin that only starts with the portal origin', async () => {
    const { send } = bff()

    const response = await send(...post('/api/messages', { text: 'a' }, { origin: `${ORIGIN}.evil.example`, 'x-csrf-token': CSRF, cookie: session }))

    expect(response.status).toBe(403)
  })

  it.each([
    ['is missing', {}],
    ['is wrong', { 'x-csrf-token': 'csrf_wrongwrongwrong' }],
    ['has another length', { 'x-csrf-token': 'x' }],
  ])('answers 403 when the CSRF header %s', async (_name, header) => {
    const { send, calls } = bff()

    const response = await send(...post('/api/messages', { text: 'a' }, { cookie: session, ...header }))

    expect(response.status).toBe(403)
    expect(await response.json()).toEqual({ code: 'CSRF' })
    expect(calls).toEqual([])
  })

  it('accepts a matching token and the portal origin', async () => {
    const { send, calls } = bff([{ body: {} }])

    const response = await send(...post('/api/messages', { text: 'a' }, { cookie: session, 'x-csrf-token': CSRF }))

    expect(response.status).toBe(200)
    expect(calls).toHaveLength(1)
  })

  it('does not ask a GET for either', async () => {
    const { send } = bff([{ body: {} }])

    const response = await send('/api/punishment', { headers: { cookie: session, origin: 'https://evil.example' } })

    expect(response.status).toBe(200)
  })
})

describe('session handling', () => {
  it('answers 401 without a session cookie and without a backend call', async () => {
    const { send, calls } = bff()

    const response = await send('/api/punishment')

    expect(response.status).toBe(401)
    expect(await response.json()).toEqual({ code: 'SESSION_EXPIRED' })
    expect(calls).toEqual([])
  })

  it('clears both cookies when the backend ends the session', async () => {
    const { send } = bff([{ status: 401 }])

    const response = await send('/api/punishment', { headers: { cookie: session } })

    expect(response.status).toBe(401)
    expect(await response.json()).toEqual({ code: 'SESSION_EXPIRED' })
    const cleared = response.headers.getSetCookie()
    for (const name of [SESSION_COOKIE, CSRF_COOKIE]) {
      expect(cleared.some((c) => c.startsWith(`${name}=;`) && /Max-Age=0|Expires=Thu, 01 Jan 1970/.test(c)), name).toBe(true)
    }
  })

  it('logs out: deletes the backend session, clears the cookies, answers 204', async () => {
    const { send, calls } = bff([{ status: 204 }])

    const response = await send('/api/session', { method: 'DELETE', headers: { origin: ORIGIN, cookie: session, 'x-csrf-token': CSRF } })

    expect(response.status).toBe(204)
    expect(calls[0]?.method).toBe('DELETE')
    expect(calls[0]?.headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
    expect(response.headers.getSetCookie()).toHaveLength(2)
  })

  it('clears the cookies on logout even when the backend is down', async () => {
    const { send } = bff([new Error('down')])

    const response = await send('/api/session', { method: 'DELETE', headers: { origin: ORIGIN, cookie: session, 'x-csrf-token': CSRF } })

    expect(response.headers.getSetCookie()).toHaveLength(2)
  })

  it('treats a logout without a session as done', async () => {
    const { send, calls } = bff()

    const response = await send('/api/session', { method: 'DELETE', headers: { origin: ORIGIN } })

    expect(response.status).toBe(204)
    expect(calls).toEqual([])
  })
})

describe('error mapping', () => {
  it.each([
    [401, {}, 401, { code: 'SESSION_EXPIRED' }],
    [403, { detail: 'staff only' }, 403, { code: 'FORBIDDEN' }],
    [404, {}, 404, { code: 'NOT_FOUND' }],
    [409, { code: 'FORFEITED' }, 409, { code: 'FORFEITED' }],
    [409, { code: 'FROZEN' }, 409, { code: 'FROZEN' }],
    [409, { code: 'NOT_ELIGIBLE', availableFrom: '2026-11-01T00:00:00Z' }, 409, { code: 'NOT_ELIGIBLE', availableFrom: '2026-11-01T00:00:00Z' }],
    [409, { code: 'SOMETHING_NEW', stack: 'trace' }, 409, { code: 'CONFLICT' }],
    [409, { code: 'NOT_ELIGIBLE', availableFrom: 'tomorrow' }, 409, { code: 'NOT_ELIGIBLE' }],
    [400, { code: 'VALIDATION' }, 400, { code: 'VALIDATION' }],
    [409, { code: 'NO_APPEAL' }, 409, { code: 'NO_APPEAL' }],
    [409, { code: 'NOT_ELIGIBLE' }, 409, { code: 'NOT_ELIGIBLE' }],
    [409, { code: 'NOT_DECIDED' }, 409, { code: 'NOT_DECIDED' }],
    [409, { code: 'SOMETHING_ELSE' }, 409, { code: 'CONFLICT' }],
    [413, {}, 413, { code: 'TOO_LARGE' }],
    [422, { message: 'ip found' }, 422, { code: 'PII_REJECTED' }],
    [400, { message: 'too long' }, 400, { code: 'VALIDATION' }],
    [429, {}, 400, { code: 'VALIDATION' }],
    [500, { trace: 'at com.example' }, 503, { code: 'UNAVAILABLE' }],
    [502, {}, 503, { code: 'UNAVAILABLE' }],
  ])('maps backend %i %j to %i %j', (status, body, expectedStatus, expectedError) => {
    expect(mapBackendError(status, body)).toEqual({ status: expectedStatus, error: expectedError })
  })

  it('relays a too long text, which the backend answers with 400, as a validation error', async () => {
    const { send } = bff([{ status: 400, body: { code: 'VALIDATION' } }])

    const response = await send(...post('/api/messages', { text: 'x'.repeat(2001) }, { cookie: session, 'x-csrf-token': CSRF }))

    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({ code: 'VALIDATION' })
  })

  it('maps a limited session reading the conversation to forbidden, without clearing the session', async () => {
    const { send } = bff([{ status: 403, body: { code: 'FORBIDDEN' } }])

    const response = await send('/api/conversation', { headers: { cookie: session } })

    expect(response.status).toBe(403)
    expect(await response.json()).toEqual({ code: 'FORBIDDEN' })
    expect(response.headers.getSetCookie()).toEqual([])
  })

  it('passes the posted message answer on', async () => {
    const { send } = bff([{ body: { messageId: 'm-1', repeated: false } }])

    const response = await send(...post('/api/messages', { text: 'a' }, { cookie: session, 'x-csrf-token': CSRF }))

    expect(await response.json()).toEqual({ messageId: 'm-1', repeated: false })
  })

  it('maps a thrown backend failure to 503 without detail', async () => {
    const { send } = bff([new Error('ECONNREFUSED 10.0.0.5:8080')])

    const response = await send('/api/punishment', { headers: { cookie: session } })

    expect(response.status).toBe(503)
    expect(await response.text()).toBe('{"code":"UNAVAILABLE"}')
  })

  it('relays no backend error text', async () => {
    const { send } = bff([{ status: 409, body: { code: 'FROZEN', message: 'internal ticket 17 of owner abc' } }])

    const response = await send(...post('/api/messages', { text: 'a' }, { cookie: session, 'x-csrf-token': CSRF }))

    expect(await response.json()).toEqual({ code: 'FROZEN' })
  })
})

describe('responses', () => {
  it('are never cached', async () => {
    const { send } = bff([{ body: { reference: 'x' } }])

    const response = await send('/api/punishment', { headers: { cookie: session } })

    expect(response.headers.get('cache-control')).toBe('no-store')
  })

  it('carry the security headers', async () => {
    const { send } = bff()

    const response = await send('/api/nothing')

    expect(response.headers.get('x-content-type-options')).toBe('nosniff')
    expect(response.headers.get('referrer-policy')).toBe('no-referrer')
    expect(response.headers.get('content-security-policy')).toContain("frame-ancestors 'none'")
  })

  it('relays the player copy of the protocol as an attachment', async () => {
    const { send, calls } = bff([{ text: 'You: hello', headers: { 'content-type': 'text/plain; charset=utf-8', 'set-cookie': 'evil=1' } }])

    const response = await send('/api/appeal/protocol?format=text', { headers: { cookie: session } })

    expect(calls[0]?.url).toBe('http://backend.test:8080/public/appeal/protocol?format=text')
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toContain('text/plain')
    expect(response.headers.get('content-disposition')).toBe('attachment; filename="appeal-protocol.txt"')
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(response.headers.getSetCookie()).toEqual([])
    expect(await response.text()).toBe('You: hello')
  })

  it('names a JSON copy accordingly', async () => {
    const { send } = bff([{ text: '{"entries":[]}', headers: { 'content-type': 'application/json' } }])

    const response = await send('/api/appeal/protocol?format=json', { headers: { cookie: session } })

    expect(response.headers.get('content-disposition')).toBe('attachment; filename="appeal-protocol.json"')
  })

  it('refuses a protocol of a type it does not relay', async () => {
    const { send } = bff([{ text: '<script>', headers: { 'content-type': 'text/html' } }])

    const response = await send('/api/appeal/protocol?format=text', { headers: { cookie: session } })

    expect(response.status).toBe(503)
  })

  it('tells an undecided appeal apart from a missing session', async () => {
    const { send } = bff([{ status: 409, body: { code: 'NOT_DECIDED' } }])

    const response = await send('/api/appeal/protocol?format=text', { headers: { cookie: session } })

    expect(response.status).toBe(409)
    expect(await response.json()).toEqual({ code: 'NOT_DECIDED' })
  })
})

describe('appeal conditions', () => {
  it('forwards the conditions version as a number together with the statement', async () => {
    const { send, calls } = bff([{ body: { status: 'ELIGIBLE_PENDING_REVIEW' } }])

    const response = await send(...post('/api/appeal', { statement: 'please', conditionsVersion: 4, extra: 'dropped' }, { cookie: session, 'x-csrf-token': CSRF }))

    expect(response.status).toBe(200)
    expect(calls[0]?.url).toBe('http://backend.test:8080/public/appeal')
    expect(JSON.parse(calls[0]!.body)).toEqual({ statement: 'please', conditionsVersion: 4 })
  })

  it.each([
    ['is missing', { statement: 'please' }],
    ['is a string', { statement: 'please', conditionsVersion: '4' }],
    ['is zero', { statement: 'please', conditionsVersion: 0 }],
    ['is fractional', { statement: 'please', conditionsVersion: 1.5 }],
    ['is too large', { statement: 'please', conditionsVersion: 2 ** 40 }],
  ])('answers 400 without calling the backend when the version %s', async (_name, body) => {
    const { send, calls } = bff()

    const response = await send(...post('/api/appeal', body, { cookie: session, 'x-csrf-token': CSRF }))

    expect(response.status).toBe(400)
    expect(await response.json()).toEqual({ code: 'BAD_REQUEST' })
    expect(calls).toEqual([])
  })

  it('passes CONDITIONS_CHANGED on as its own code', async () => {
    const { send } = bff([{ status: 409, body: { code: 'CONDITIONS_CHANGED', message: 'internal detail' } }])

    const response = await send(...post('/api/appeal', { statement: 'please', conditionsVersion: 1 }, { cookie: session, 'x-csrf-token': CSRF }))

    expect(response.status).toBe(409)
    expect(await response.json()).toEqual({ code: 'CONDITIONS_CHANGED' })
  })

  it('serves the conditions with a session and relays them as they are', async () => {
    const { send, calls } = bff([{ body: { version: 2, text: 'rules' } }])

    const response = await send('/api/appeal/conditions', { headers: { cookie: session } })

    expect(await response.json()).toEqual({ version: 2, text: 'rules' })
    expect(calls[0]?.url).toBe('http://backend.test:8080/public/appeal/conditions')
    expect(response.headers.get('cache-control')).toBe('no-store')
  })

  it('refuses the conditions without a session cookie', async () => {
    const { send, calls } = bff()

    const response = await send('/api/appeal/conditions')

    expect(response.status).toBe(401)
    expect(calls).toEqual([])
  })
})
