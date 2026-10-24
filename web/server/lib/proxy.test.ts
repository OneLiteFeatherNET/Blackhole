// @vitest-environment node
import { describe, expect, it } from 'vitest'
import {
  DEFAULT_TIMEOUT_MS,
  EVIDENCE_UPLOAD_TIMEOUT_MS,
  DEFAULT_MAX_BODY_BYTES,
  EVIDENCE_UPLOAD_MAX_BYTES,
  HostilePathError,
  buildBackendUrl,
  forwardToBackend,
  selectMaxBodyBytes,
  selectTimeoutMs,
  type ProxyRequest,
} from './proxy'

const BACKEND = 'http://backend:8080'

interface Call {
  url: URL
  init: RequestInit
}

/** A fetch that records its calls and answers with `respond`; like the real one it honours an aborted signal. */
function fakeFetch(respond: () => Response | Promise<Response> = () => new Response('{}', { status: 200 })) {
  const calls: Call[] = []
  const fetchImpl = ((input: URL, init: RequestInit) => {
    calls.push({ url: input, init })
    if (init.signal?.aborted) return Promise.reject(init.signal.reason)
    return Promise.resolve(respond())
  }) as unknown as typeof fetch
  return { calls, fetchImpl }
}

function request(overrides: Partial<ProxyRequest> & { fetch: typeof fetch }): ProxyRequest {
  return { backendUrl: BACKEND, method: 'GET', path: '/api/x', headers: new Headers(), proto: 'http', ...overrides }
}

describe('buildBackendUrl', () => {
  it('removes the /api prefix', () => {
    expect(buildBackendUrl(BACKEND, '/api/player/resolve/Steve').href).toBe('http://backend:8080/player/resolve/Steve')
  })

  it('keeps the query string', () => {
    expect(buildBackendUrl(BACKEND, '/api/punishments?page=2&size=10').href).toBe('http://backend:8080/punishments?page=2&size=10')
  })

  it('keeps a path prefix of the backend URL', () => {
    expect(buildBackendUrl('https://host/base', '/api/x').href).toBe('https://host/base/x')
  })

  it('keeps ordinary percent-encoded characters', () => {
    expect(buildBackendUrl(BACKEND, '/api/player/a%20b').pathname).toBe('/player/a%20b')
  })

  it.each([
    ['dot-dot segment', '/api/../admin'],
    ['encoded dot-dot', '/api/%2e%2e/admin'],
    ['mixed-case encoded dot-dot', '/api/%2E%2e/admin'],
    ['dot segment', '/api/./x'],
    ['double slash authority', '/api//evil.example/x'],
    ['scheme and authority', '/api/http://evil.example/x'],
    ['encoded slash', '/api/a%2fb'],
    ['upper-case encoded slash', '/api/a%2Fb'],
    ['backslash', '/api/a\\b'],
    ['encoded backslash', '/api/a%5cb'],
    ['encoded NUL', '/api/a%00b'],
    ['control character', '/api/a\u0001b'],
    ['not under /api', '/other/x'],
    ['prefix lookalike', '/apix/y'],
  ])('rejects %s', (_label, path) => {
    expect(() => buildBackendUrl(BACKEND, path)).toThrow(HostilePathError)
  })

  it('accepts the bare /api path', () => {
    expect(buildBackendUrl(BACKEND, '/api').href).toBe('http://backend:8080/')
  })
})

describe('selectTimeoutMs', () => {
  it('gives evidence uploads 120 seconds', () => {
    expect(selectTimeoutMs('POST', '/evidence/file')).toBe(EVIDENCE_UPLOAD_TIMEOUT_MS)
  })

  it('gives other POST requests 30 seconds', () => {
    expect(selectTimeoutMs('POST', '/punishments')).toBe(DEFAULT_TIMEOUT_MS)
  })

  it('gives reads of the evidence file route 30 seconds', () => {
    expect(selectTimeoutMs('GET', '/evidence/file')).toBe(DEFAULT_TIMEOUT_MS)
  })
})

function streamOf(...chunks: number[]): ReadableStream<Uint8Array> {
  const queue = chunks.map((size) => new Uint8Array(size))
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      const next = queue.shift()
      if (next) controller.enqueue(next)
      else controller.close()
    },
  })
}

/** Like the real fetch, drains a streamed request body and rejects when the stream errors. */
function drainingFetch() {
  const calls: Call[] = []
  const fetchImpl = (async (input: URL, init: RequestInit) => {
    calls.push({ url: input, init })
    const reader = (init.body as ReadableStream<Uint8Array>).getReader()
    while (!(await reader.read()).done);
    return new Response('{}')
  }) as unknown as typeof fetch
  return { calls, fetchImpl }
}

describe('selectMaxBodyBytes', () => {
  it('allows 128 MiB for the evidence upload', () => {
    expect(selectMaxBodyBytes('/evidence/file')).toBe(134217728)
    expect(EVIDENCE_UPLOAD_MAX_BYTES).toBe(134217728)
  })

  it('keeps the nginx default of 1 MiB elsewhere', () => {
    expect(selectMaxBodyBytes('/punishments')).toBe(1048576)
    expect(DEFAULT_MAX_BODY_BYTES).toBe(1048576)
  })
})

describe('forwardToBackend body limit', () => {
  const upload = (extra: Partial<ProxyRequest> & { fetch: typeof fetch }) =>
    request({ method: 'POST', path: '/api/evidence/file', ...extra })

  it('answers 413 for a Content-Length above the evidence cap without calling the backend', async () => {
    const { calls, fetchImpl } = fakeFetch()
    const headers = new Headers({ 'content-length': String(EVIDENCE_UPLOAD_MAX_BYTES + 1) })

    const response = await forwardToBackend(upload({ fetch: fetchImpl, headers, body: streamOf(1) }))

    expect(response.status).toBe(413)
    expect(calls, 'fetch was not called').toHaveLength(0)
  })

  it('lets a Content-Length exactly at the evidence cap pass', async () => {
    const { calls, fetchImpl } = fakeFetch()
    const headers = new Headers({ 'content-length': String(EVIDENCE_UPLOAD_MAX_BYTES) })

    const response = await forwardToBackend(upload({ fetch: fetchImpl, headers, body: streamOf(1) }))

    expect(response.status).toBe(200)
    expect(calls).toHaveLength(1)
  })

  it('answers 413 for a Content-Length above 1 MiB on other routes without calling the backend', async () => {
    const { calls, fetchImpl } = fakeFetch()
    const headers = new Headers({ 'content-length': String(DEFAULT_MAX_BODY_BYTES + 1) })

    const response = await forwardToBackend(request({ fetch: fetchImpl, method: 'POST', path: '/api/punishments', headers, body: streamOf(1) }))

    expect(response.status).toBe(413)
    expect(calls, 'fetch was not called').toHaveLength(0)
  })

  it('answers 413 once a streamed body without Content-Length passes the cap', async () => {
    const { calls, fetchImpl } = drainingFetch()

    const response = await forwardToBackend(
      request({ fetch: fetchImpl, method: 'POST', path: '/api/punishments', body: streamOf(DEFAULT_MAX_BODY_BYTES, 1) }),
    )

    expect(response.status).toBe(413)
    expect(calls, 'the upstream request was started and then aborted').toHaveLength(1)
  })

  it('lets a streamed body exactly at the cap pass', async () => {
    const { fetchImpl } = drainingFetch()

    const response = await forwardToBackend(
      request({ fetch: fetchImpl, method: 'POST', path: '/api/punishments', body: streamOf(DEFAULT_MAX_BODY_BYTES) }),
    )

    expect(response.status).toBe(200)
  })

  it('applies the evidence cap to a streamed upload past 128 MiB', async () => {
    const { fetchImpl } = drainingFetch()
    const chunk = 16 * 1024 * 1024

    const response = await forwardToBackend(upload({ fetch: fetchImpl, body: streamOf(...Array(8).fill(chunk), 1) }))

    expect(response.status).toBe(413)
  })
})

describe('the case event stream', () => {
  it('gives the stream no read timeout, because it stays open while the panel listens', () => {
    expect(selectTimeoutMs('GET', '/case/p-1/events')).toBeNull()
  })

  it('keeps the 30 second timeout for the other case reads', () => {
    expect(selectTimeoutMs('GET', '/case/p-1/timeline')).toBe(DEFAULT_TIMEOUT_MS)
  })

  it('forwards the stream without a timeout signal', async () => {
    const { calls, fetchImpl } = fakeFetch()

    await forwardToBackend(request({ fetch: fetchImpl, path: '/api/case/p-1/events' }))

    expect(calls[0].init.signal).toBeUndefined()
  })
})

describe('forwardToBackend', () => {
  it('sends /api/x to <backend>/x with method and query', async () => {
    const { calls, fetchImpl } = fakeFetch()

    await forwardToBackend(request({ fetch: fetchImpl, method: 'PUT', path: '/api/x?a=1' }))

    expect(calls).toHaveLength(1)
    expect(calls[0].url.href).toBe('http://backend:8080/x?a=1')
    expect(calls[0].init.method).toBe('PUT')
  })

  it('sets X-Forwarded-For and X-Forwarded-Proto and does not forward the incoming values', async () => {
    const { calls, fetchImpl } = fakeFetch()
    const headers = new Headers({ 'x-forwarded-for': '6.6.6.6', 'x-forwarded-proto': 'spoofed', host: 'ui.example' })

    await forwardToBackend(request({ fetch: fetchImpl, headers, clientAddress: '10.1.2.3', proto: 'https' }))

    const sent = calls[0].init.headers as Headers
    expect(sent.get('x-forwarded-for')).toBe('10.1.2.3')
    expect(sent.get('x-forwarded-proto')).toBe('https')
    expect(sent.has('host'), 'host is left to fetch, which uses the backend host').toBe(false)
  })

  it('forwards ordinary request headers but not hop-by-hop ones', async () => {
    const { calls, fetchImpl } = fakeFetch()
    const headers = new Headers({ 'content-type': 'application/json', authorization: 'Bearer t', connection: 'keep-alive', 'transfer-encoding': 'chunked' })

    await forwardToBackend(request({ fetch: fetchImpl, headers }))

    const sent = calls[0].init.headers as Headers
    expect(sent.get('content-type')).toBe('application/json')
    expect(sent.get('authorization')).toBe('Bearer t')
    expect(sent.has('connection'), 'connection').toBe(false)
    expect(sent.has('transfer-encoding'), 'transfer-encoding').toBe(false)
  })

  it('does not forward Expect, which fetch rejects', async () => {
    const { calls, fetchImpl } = fakeFetch()

    await forwardToBackend(request({ fetch: fetchImpl, headers: new Headers({ expect: '100-continue' }) }))

    expect((calls[0].init.headers as Headers).has('expect')).toBe(false)
  })

  it('relays status and body and marks the response no-store', async () => {
    const { fetchImpl } = fakeFetch(() => new Response('{"a":1}', { status: 201, headers: { 'content-type': 'application/json', 'cache-control': 'max-age=60' } }))

    const response = await forwardToBackend(request({ fetch: fetchImpl }))

    expect(response.status).toBe(201)
    expect(await response.text()).toBe('{"a":1}')
    expect(response.headers.get('content-type')).toBe('application/json')
    expect(response.headers.get('cache-control')).toBe('no-store')
  })

  it('drops backend headers that would conflict with the UI server', async () => {
    const { fetchImpl } = fakeFetch(
      () =>
        new Response('x', {
          headers: { server: 'Netty', 'x-powered-by': 'Micronaut', 'x-frame-options': 'SAMEORIGIN', 'content-security-policy': 'default-src *', 'content-encoding': 'gzip' },
        }),
    )

    const response = await forwardToBackend(request({ fetch: fetchImpl }))

    for (const name of ['server', 'x-powered-by', 'x-frame-options', 'content-security-policy', 'content-encoding']) {
      expect(response.headers.has(name), name).toBe(false)
    }
  })

  it('keeps every Set-Cookie header of the backend', async () => {
    const upstream = new Headers()
    upstream.append('set-cookie', 'a=1')
    upstream.append('set-cookie', 'b=2')
    const { fetchImpl } = fakeFetch(() => new Response('x', { headers: upstream }))

    const response = await forwardToBackend(request({ fetch: fetchImpl }))

    expect(response.headers.getSetCookie()).toEqual(['a=1', 'b=2'])
  })

  it('streams the request body to the backend instead of buffering it', async () => {
    const { calls, fetchImpl } = fakeFetch()
    const body = new ReadableStream<Uint8Array>({ start: (c) => c.close() })

    await forwardToBackend(request({ fetch: fetchImpl, method: 'POST', path: '/api/evidence/file', body }))

    expect(calls[0].init.body, 'a stream, not a buffer').toBeInstanceOf(ReadableStream)
    expect((calls[0].init as { duplex?: string }).duplex).toBe('half')
  })

  it('does not send a body with a GET request', async () => {
    const { calls, fetchImpl } = fakeFetch()

    await forwardToBackend(request({ fetch: fetchImpl, method: 'GET', body: new ReadableStream() }))

    expect(calls[0].init.body).toBeUndefined()
  })

  it('does not follow backend redirects', async () => {
    const { calls, fetchImpl } = fakeFetch()

    await forwardToBackend(request({ fetch: fetchImpl }))

    expect(calls[0].init.redirect).toBe('manual')
  })

  it('answers 502 when the backend is unreachable', async () => {
    const { fetchImpl } = fakeFetch(() => {
      throw new TypeError('fetch failed')
    })

    const response = await forwardToBackend(request({ fetch: fetchImpl }))

    expect(response.status).toBe(502)
    expect(response.headers.get('cache-control')).toBe('no-store')
  })

  it('answers 504 when the signal has timed out', async () => {
    const { fetchImpl } = fakeFetch()
    const controller = new AbortController()
    controller.abort(new DOMException('timed out', 'TimeoutError'))

    const response = await forwardToBackend(request({ fetch: fetchImpl, signal: controller.signal }))

    expect(response.status).toBe(504)
  })

  it('answers 504 when the signal was aborted', async () => {
    const { fetchImpl } = fakeFetch()
    const controller = new AbortController()
    controller.abort()

    const response = await forwardToBackend(request({ fetch: fetchImpl, signal: controller.signal }))

    expect(response.status).toBe(504)
  })

  it('uses a timeout signal when none is injected', async () => {
    const { calls, fetchImpl } = fakeFetch()

    await forwardToBackend(request({ fetch: fetchImpl }))

    expect(calls[0].init.signal, 'a signal is set').toBeInstanceOf(AbortSignal)
    expect(calls[0].init.signal?.aborted, 'not yet aborted').toBe(false)
  })

  it.each([
    '/api/../admin',
    '/api/%2e%2e/admin',
    '/api//evil.example/x',
    '/api/http://evil.example/x',
    '/api/a%2fb',
    '/api/a\\b',
  ])('answers 400 for %s without calling the backend', async (path) => {
    const { calls, fetchImpl } = fakeFetch()

    const response = await forwardToBackend(request({ fetch: fetchImpl, path }))

    expect(response.status).toBe(400)
    expect(calls, 'fetch was not called').toHaveLength(0)
  })
})
