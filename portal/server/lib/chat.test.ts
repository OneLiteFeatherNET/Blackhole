// @vitest-environment node
import { createApp, eventHandler, toWebHandler } from 'h3'
import { describe, expect, it } from 'vitest'
import { CSRF_COOKIE, MAX_UPLOAD_BYTES, SESSION_COOKIE } from '../../src/api/contract'
import { createBffHandler } from './bff'
import { parsePortalConfig } from './config'
import { applySecurityHeaders, sendErrorResponse } from './handlers'

const ORIGIN = 'https://appeal.example.org'
const TOKEN = 'tok_0123456789abcdefghijklmnopqrstuvwxyz'
const CSRF = 'csrf_0123456789abcdef'
const SESSION = `${SESSION_COOKIE}=${TOKEN}; ${CSRF_COOKIE}=${CSRF}`
const FILE_ID = '3f2b8c1e-aaaa-4bbb-8ccc-0123456789ab'
const BOUNDARY = 'bh-test-boundary'

interface Recorded {
  url: string
  method: string
  headers: Headers
  body: Uint8Array
  signal: AbortSignal | undefined
  upstreamCancelled: boolean
}

type Answer = (recorded: Recorded) => Response | Promise<Response>

/**
 * A fake backend with no network and no timers. It drains a request body (so a streamed upload is
 * really read), records what it saw and answers with the given function.
 */
function fakeBackend(answer: Answer) {
  const calls: Recorded[] = []
  const impl = (async (input: string | URL | Request, init?: RequestInit & { duplex?: string }) => {
    const body = await drain(init?.body)
    const recorded: Recorded = {
      url: String(input),
      method: init?.method ?? 'GET',
      headers: new Headers(init?.headers),
      body,
      signal: init?.signal ?? undefined,
      upstreamCancelled: false,
    }
    calls.push(recorded)
    return answer(recorded)
  }) as unknown as typeof fetch
  return { calls, impl }
}

async function drain(body: BodyInit | null | undefined): Promise<Uint8Array> {
  if (body === undefined || body === null) return new Uint8Array()
  if (typeof body === 'string') return new TextEncoder().encode(body)
  if (body instanceof Uint8Array) return body
  if (body instanceof ReadableStream) {
    const reader = body.getReader()
    const parts: Uint8Array[] = []
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      parts.push(value)
    }
    return Buffer.concat(parts)
  }
  return new Uint8Array()
}

function bff(answer: Answer) {
  const backend = fakeBackend(answer)
  const config = parsePortalConfig({ NODE_ENV: 'production', BLACKHOLE_PORTAL_ORIGIN: ORIGIN, BLACKHOLE_BACKEND_URL: 'http://backend.test:8080' })
  const app = createApp({ onError: (error, event) => sendErrorResponse(error, event) })
  app.use(eventHandler((event) => applySecurityHeaders(event)))
  app.use(createBffHandler({ config, fetchImpl: backend.impl }))
  const web = toWebHandler(app)
  return { calls: backend.calls, send: (path: string, init?: RequestInit) => web(new Request(`http://portal.test${path}`, init)) }
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

function multipart(size: number, { type = `multipart/form-data; boundary=${BOUNDARY}`, length = size }: { type?: string; length?: number } = {}) {
  const payload = new Uint8Array(size).fill(0x61)
  return { body: payload, headers: { 'content-type': type, 'content-length': String(length), origin: ORIGIN, 'x-csrf-token': CSRF, cookie: SESSION } }
}

function upload(size: number, extra: Partial<{ type: string; length: number }> = {}, cookie = SESSION) {
  const request = multipart(size, extra)
  return ['/api/attachments', { method: 'POST', body: request.body, headers: { ...request.headers, cookie } } as RequestInit] as const
}

describe('chat file upload', () => {
  it('streams the file through to the backend as a multipart body', async () => {
    const { send, calls } = bff(() => json(200, { attachmentId: FILE_ID, name: 'a.png', contentType: 'image/png', size: 3 }))

    const response = await send(...upload(3))

    expect(response.status).toBe(200)
    expect(calls[0]?.url).toBe('http://backend.test:8080/public/attachments')
    expect(calls[0]?.headers.get('content-type')).toBe(`multipart/form-data; boundary=${BOUNDARY}`)
    expect(calls[0]?.body.byteLength).toBe(3)
    expect(calls[0]?.headers.get('authorization')).toBe(`Bearer ${TOKEN}`)
  })

  it('answers only the staged fields and drops anything else the backend sends', async () => {
    const { send } = bff(() => json(200, { attachmentId: FILE_ID, name: 'a.png', contentType: 'image/png', size: 3, width: 2, height: 1, path: '/secret/place', sha256: 'x' }))

    const response = await send(...upload(3))

    expect(await response.json()).toEqual({ attachmentId: FILE_ID, name: 'a.png', contentType: 'image/png', size: 3, width: 2, height: 1 })
  })

  it('refuses a body that is not multipart without calling the backend', async () => {
    const { send, calls } = bff(() => json(200, {}))

    const response = await send(...upload(3, { type: 'application/json' }))

    expect(response.status).toBe(400)
    expect(calls).toEqual([])
  })

  it('refuses an upload without a declared length without calling the backend', async () => {
    const { send, calls } = bff(() => json(200, {}))
    const [path, init] = upload(3)
    const headers = new Headers(init.headers)
    headers.delete('content-length')

    const response = await send(path, { ...init, headers })

    expect(response.status).toBe(400)
    expect(calls).toEqual([])
  })

  it('refuses a declared length above the upload cap before reading anything', async () => {
    const { send, calls } = bff(() => json(200, {}))

    const response = await send(...upload(3, { length: MAX_UPLOAD_BYTES + 1 }))

    expect(response.status).toBe(413)
    expect(await response.json()).toEqual({ code: 'TOO_LARGE' })
    expect(calls).toEqual([])
  })

  it('cuts off a body that grows past the cap while it is streamed', async () => {
    const { send } = bff(async ({ body }) => {
      // The backend would read the whole body; the BFF errors the stream once it passes the cap.
      if (body.byteLength > MAX_UPLOAD_BYTES) throw new Error('unreachable: the body was not capped')
      return json(200, {})
    })
    const oversize = new Uint8Array(MAX_UPLOAD_BYTES + 1)
    const [path, init] = upload(0)

    const response = await send(path, { ...init, body: oversize })

    expect(response.status).toBe(413)
    expect(await response.json()).toEqual({ code: 'TOO_LARGE' })
  })

  it.each([
    [409, { code: 'NO_APPEAL' }, 'NO_APPEAL'],
    [409, { code: 'QUOTA' }, 'QUOTA'],
    [409, { code: 'something-else' }, 'CONFLICT'],
    [415, { code: 'UNSUPPORTED_TYPE' }, 'UNSUPPORTED_TYPE'],
    [422, { code: 'FILE_FLAGGED' }, 'FILE_FLAGGED'],
    [422, { code: 'FILE_ACTIVE_CONTENT' }, 'FILE_ACTIVE_CONTENT'],
    [422, { code: 'INVALID_FILE' }, 'INVALID_FILE'],
    [422, { code: 'PII' }, 'INVALID_FILE'],
    [503, { code: 'UNAVAILABLE' }, 'UNAVAILABLE'],
  ])('maps a backend %s %j to %s', async (status, body, code) => {
    const { send } = bff(() => json(status, body))

    const response = await send(...upload(3))

    expect(response.status).toBe(status >= 500 ? 503 : status)
    expect(await response.json()).toEqual({ code })
  })

  it('answers 403 when the session is limited, and passes the refusal on', async () => {
    const { send } = bff(() => json(403, { code: 'FORBIDDEN' }))

    const response = await send(...upload(3))

    expect(response.status).toBe(403)
    expect(await response.json()).toEqual({ code: 'FORBIDDEN' })
  })

  it('answers 401 and clears the cookies when the backend ends the session', async () => {
    const { send } = bff(() => json(401, {}))

    const response = await send(...upload(3))

    expect(response.status).toBe(401)
    expect(response.headers.get('set-cookie')).toContain(SESSION_COOKIE)
  })

  it('answers 503 when the backend cannot be reached', async () => {
    const { send } = bff(() => {
      throw new Error('down')
    })

    const response = await send(...upload(3))

    expect(response.status).toBe(503)
    expect(await response.json()).toEqual({ code: 'UNAVAILABLE' })
  })

  it('refuses an upload without a session cookie', async () => {
    const { send, calls } = bff(() => json(200, {}))
    const [path, init] = upload(3, {}, '')

    const response = await send(path, init)

    expect(response.status).toBe(401)
    expect(calls).toEqual([])
  })

  it('refuses an upload with a foreign Origin or a missing CSRF token', async () => {
    const { send, calls } = bff(() => json(200, {}))
    const [path, init] = upload(3)
    const foreign = new Headers(init.headers)
    foreign.set('origin', 'https://evil.example')
    const noCsrf = new Headers(init.headers)
    noCsrf.delete('x-csrf-token')

    expect((await send(path, { ...init, headers: foreign })).status).toBe(403)
    expect((await send(path, { ...init, headers: noCsrf })).status).toBe(403)
    expect(calls).toEqual([])
  })
})

describe('chat file download', () => {
  const bytes = new TextEncoder().encode('\x89PNG fake bytes')

  it('shows an image inline with the sandbox policy and no storage', async () => {
    const { send, calls } = bff(() => new Response(bytes, { status: 200, headers: { 'content-type': 'image/png' } }))

    const response = await send(`/api/attachments/${FILE_ID}`, { headers: { cookie: SESSION } })

    expect(calls[0]?.url).toBe(`http://backend.test:8080/public/attachments/${FILE_ID}`)
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toBe('image/png')
    expect(response.headers.get('content-disposition')).toBe('inline')
    expect(response.headers.get('content-security-policy')).toBe('sandbox')
    expect(response.headers.get('cache-control')).toBe('private, no-store')
    expect(response.headers.get('x-content-type-options')).toBe('nosniff')
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(bytes)
  })

  it.each(['application/pdf', 'text/plain'])('sends a %s as an attachment', async (type) => {
    const { send } = bff(() => new Response(bytes, { status: 200, headers: { 'content-type': type } }))

    const response = await send(`/api/attachments/${FILE_ID}`, { headers: { cookie: SESSION } })

    expect(response.headers.get('content-disposition')).toBe('attachment')
  })

  it('refuses a type outside the allow-list instead of relaying it', async () => {
    const { send } = bff(() => new Response('<svg onload="x()"></svg>', { status: 200, headers: { 'content-type': 'image/svg+xml' } }))

    const response = await send(`/api/attachments/${FILE_ID}`, { headers: { cookie: SESSION } })

    expect(response.status).toBe(503)
    expect(response.headers.get('content-type')).not.toContain('svg')
  })

  it('answers 404 for a file the backend does not show', async () => {
    const { send } = bff(() => json(404, { code: 'NOT_FOUND' }))

    const response = await send(`/api/attachments/${FILE_ID}`, { headers: { cookie: SESSION } })

    expect(response.status).toBe(404)
    expect(await response.json()).toEqual({ code: 'NOT_FOUND' })
  })

  it('does not forward an identifier that is not a plain token', async () => {
    const { send, calls } = bff(() => new Response(bytes, { status: 200, headers: { 'content-type': 'image/png' } }))

    const response = await send('/api/attachments/..%2Fsession', { headers: { cookie: SESSION } })

    expect(response.status).toBe(404)
    expect(calls).toEqual([])
  })

  it('answers 401 without a session cookie and without a backend call', async () => {
    const { send, calls } = bff(() => new Response(bytes))

    const response = await send(`/api/attachments/${FILE_ID}`)

    expect(response.status).toBe(401)
    expect(calls).toEqual([])
  })
})

describe('chat file delete', () => {
  it('passes the 204 of a removed staged file through without touching the session', async () => {
    const { send, calls } = bff(() => new Response(null, { status: 204 }))

    const response = await send(`/api/attachments/${FILE_ID}`, { method: 'DELETE', headers: { origin: ORIGIN, 'x-csrf-token': CSRF, cookie: SESSION } })

    expect(response.status).toBe(204)
    expect(calls[0]?.url).toBe(`http://backend.test:8080/public/attachments/${FILE_ID}`)
    expect(calls[0]?.method).toBe('DELETE')
    expect(response.headers.get('set-cookie')).toBeNull()
  })

  it('answers 404 when the file is not a staged one of this appeal', async () => {
    const { send } = bff(() => json(404, { code: 'NOT_FOUND' }))

    const response = await send(`/api/attachments/${FILE_ID}`, { method: 'DELETE', headers: { origin: ORIGIN, 'x-csrf-token': CSRF, cookie: SESSION } })

    expect(response.status).toBe(404)
  })

  it('needs the CSRF token like every state change', async () => {
    const { send, calls } = bff(() => new Response(null, { status: 204 }))

    const response = await send(`/api/attachments/${FILE_ID}`, { method: 'DELETE', headers: { origin: ORIGIN, cookie: SESSION } })

    expect(response.status).toBe(403)
    expect(calls).toEqual([])
  })
})

describe('live stream pass-through', () => {
  const frames = 'retry: 5000\n\nevent: ready\ndata: {"seq":7}\n\n: ka\n\nevent: changed\nid: 8\ndata: {"seq":8}\n\n'

  function streamOf(chunks: string[], onCancel?: () => void) {
    const encoder = new TextEncoder()
    return new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
        controller.close()
      },
      cancel() {
        onCancel?.()
      },
    })
  }

  it('passes the frames through unchanged with the streaming headers and no length', async () => {
    const { send, calls } = bff(() => new Response(streamOf([frames]), { status: 200, headers: { 'content-type': 'text/event-stream' } }))

    const response = await send('/api/conversation/events', { headers: { cookie: SESSION } })

    expect(calls[0]?.url).toBe('http://backend.test:8080/public/conversation/events')
    expect(calls[0]?.headers.get('accept')).toBe('text/event-stream')
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toBe('text/event-stream; charset=utf-8')
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(response.headers.get('x-accel-buffering')).toBe('no')
    expect(response.headers.get('content-length')).toBeNull()
    expect(response.headers.get('content-encoding')).toBeNull()
    expect(await response.text()).toBe(frames)
  })

  it('forwards a well-formed Last-Event-ID and drops a malformed one', async () => {
    const good = bff(() => new Response(streamOf([]), { status: 200, headers: { 'content-type': 'text/event-stream' } }))
    await good.send('/api/conversation/events', { headers: { cookie: SESSION, 'last-event-id': '41' } })
    const bad = bff(() => new Response(streamOf([]), { status: 200, headers: { 'content-type': 'text/event-stream' } }))
    await bad.send('/api/conversation/events', { headers: { cookie: SESSION, 'last-event-id': '4 1;x' } })

    expect(good.calls[0]?.headers.get('last-event-id')).toBe('41')
    expect(bad.calls[0]?.headers.get('last-event-id')).toBeNull()
  })

  it('answers a bare 401 without a session, so the browser stops reconnecting', async () => {
    const { send, calls } = bff(() => new Response(streamOf([]), { status: 200, headers: { 'content-type': 'text/event-stream' } }))

    const response = await send('/api/conversation/events')

    expect(response.status).toBe(401)
    expect(await response.text()).toBe('')
    expect(calls).toEqual([])
  })

  it('answers 401 when the backend ends the session, and clears the cookies', async () => {
    const { send } = bff(() => json(401, {}))

    const response = await send('/api/conversation/events', { headers: { cookie: SESSION } })

    expect(response.status).toBe(401)
    expect(response.headers.get('set-cookie')).toContain(SESSION_COOKIE)
  })

  it('answers 403 for a limited session and does not stream', async () => {
    const { send } = bff(() => json(403, { code: 'FORBIDDEN' }))

    const response = await send('/api/conversation/events', { headers: { cookie: SESSION } })

    expect(response.status).toBe(403)
    expect(response.headers.get('content-type')).toContain('application/json')
  })

  it('refuses an upstream answer that is not an event stream', async () => {
    const { send } = bff(() => json(200, { entries: [] }))

    const response = await send('/api/conversation/events', { headers: { cookie: SESSION } })

    expect(response.status).toBe(503)
  })

  it('gives the upstream request an abort signal that stays unfired while the stream runs', async () => {
    const { send, calls } = bff(() => new Response(streamOf(['data: x\n\n']), { status: 200, headers: { 'content-type': 'text/event-stream' } }))

    await send('/api/conversation/events', { headers: { cookie: SESSION } })

    expect(calls[0]?.signal).toBeDefined()
    expect(calls[0]?.signal?.aborted).toBe(false)
  })
})

describe('messages with files', () => {
  it('forwards the attachment ids and lets the text be absent', async () => {
    const { send, calls } = bff(() => json(200, { messageId: 'm1', repeated: false }))

    await send('/api/messages', {
      method: 'POST',
      headers: { origin: ORIGIN, 'x-csrf-token': CSRF, cookie: SESSION, 'content-type': 'application/json' },
      body: JSON.stringify({ attachmentIds: [FILE_ID], punishment: 'other' }),
    })

    expect(JSON.parse(new TextDecoder().decode(calls[0]!.body))).toEqual({ attachmentIds: [FILE_ID] })
  })

  it('refuses more than five attachment ids', async () => {
    const { send, calls } = bff(() => json(200, {}))
    const six = Array.from({ length: 6 }, (_, i) => `00000000-0000-4000-8000-00000000000${i}`)

    const response = await send('/api/messages', {
      method: 'POST',
      headers: { origin: ORIGIN, 'x-csrf-token': CSRF, cookie: SESSION, 'content-type': 'application/json' },
      body: JSON.stringify({ attachmentIds: six }),
    })

    expect(response.status).toBe(400)
    expect(calls).toEqual([])
  })

  it('maps a 409 FILES_UNAVAILABLE to its own code', async () => {
    const { send } = bff(() => json(409, { code: 'FILES_UNAVAILABLE' }))

    const response = await send('/api/messages', {
      method: 'POST',
      headers: { origin: ORIGIN, 'x-csrf-token': CSRF, cookie: SESSION, 'content-type': 'application/json' },
      body: JSON.stringify({ attachmentIds: [FILE_ID] }),
    })

    expect(response.status).toBe(409)
    expect(await response.json()).toEqual({ code: 'FILES_UNAVAILABLE' })
  })
})
