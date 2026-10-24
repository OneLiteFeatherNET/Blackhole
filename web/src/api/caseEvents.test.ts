import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HttpAuth } from './http'
import { openCaseEvents, type CaseEventsState } from './caseEvents'

const REF = 'p-1'
const BASE = 'http://ui.test/api'
const encoder = new TextEncoder()

/** A streamed answer: the chunks are queued at once, `end` closes the body. */
function streamResponse(chunks: string[]) {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c
      for (const chunk of chunks) c.enqueue(encoder.encode(chunk))
    },
  })
  return { response: new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }), end: () => controller.close() }
}

interface Connection {
  headers: Headers
  signal: AbortSignal
}

/** A fetch that records every connection and answers from a script: a stream, a status, or a network error. */
function scriptedFetch(script: Array<{ chunks?: string[]; status?: number } | Error>) {
  const connections: Connection[] = []
  const ends: Array<() => void> = []
  const impl = vi.fn(async (input: Request | string | URL, init?: RequestInit): Promise<Response> => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://ui.test'), init)
    connections.push({ headers: request.headers, signal: request.signal })
    const next = script.shift()
    if (next instanceof Error) throw next
    if (next?.status !== undefined && next.status !== 200) return new Response(null, { status: next.status })
    const stream = streamResponse(next?.chunks ?? [])
    ends.push(stream.end)
    return stream.response
  })
  return { connections, ends, impl: impl as unknown as typeof fetch }
}

/** An auth whose token sequence is given; the last entry repeats. */
function auth(tokens: Array<string | null> = ['token-1']) {
  const events = { forbidden: 0, signIns: 0, renewals: 0 }
  const queue = [...tokens]
  const httpAuth: HttpAuth = {
    getAccessToken: async () => (queue.length > 1 ? queue.shift()! : (queue[0] ?? null)),
    renew: async () => {
      events.renewals++
      return 'renewed'
    },
    signIn: () => {
      events.signIns++
    },
    forbidden: () => {
      events.forbidden++
    },
  }
  return { auth: httpAuth, events }
}

/** Lets the promise callbacks of the stream run; no wall-clock time passes. */
async function settle(rounds = 20): Promise<void> {
  for (let i = 0; i < rounds; i++) await Promise.resolve()
}

describe('openCaseEvents', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('reports the seq of ready and changed frames', async () => {
    const changed: number[] = []
    const ready: number[] = []
    const script = scriptedFetch([{ chunks: ['retry: 5000\n\nevent: ready\ndata: {"seq":4}\n\n', 'event: changed\nid: 5\ndata: {"seq":5}\n\n'] }])
    openCaseEvents({ baseUrl: BASE, auth: auth().auth, reference: REF, fetch: script.impl, onChanged: (seq) => changed.push(seq), onReady: (seq) => ready.push(seq) })
    await settle()

    expect(ready).toEqual([4])
    expect(changed).toEqual([5])
  })

  it('ignores keep-alive comments and unknown event names', async () => {
    const changed: number[] = []
    const script = scriptedFetch([{ chunks: [': ka\n\nevent: something-new\ndata: {}\n\n'] }])
    openCaseEvents({ baseUrl: BASE, auth: auth().auth, reference: REF, fetch: script.impl, onChanged: (seq) => changed.push(seq) })
    await settle()

    expect(changed).toEqual([])
  })

  it('asks for the case events of the reference with a fresh bearer token on every connection', async () => {
    const script = scriptedFetch([{ chunks: [] }, { chunks: [] }])
    openCaseEvents({ baseUrl: BASE, auth: auth(['token-1', 'token-2']).auth, reference: REF, fetch: script.impl, onChanged: () => {} })
    await settle()
    script.ends[0]!()
    await settle()
    vi.advanceTimersByTime(1000)
    await settle()

    expect(script.impl).toHaveBeenCalledTimes(2)
    expect(script.connections.map((connection) => connection.headers.get('Authorization'))).toEqual(['Bearer token-1', 'Bearer token-2'])
    expect(script.connections[0]!.headers.get('X-API-VERSION')).toBe('1')
  })

  it('reconnects with the last changed id as Last-Event-ID once the stream has ended', async () => {
    const script = scriptedFetch([{ chunks: ['event: changed\nid: 9\ndata: {"seq":9}\n\n'] }, { chunks: [] }])
    openCaseEvents({ baseUrl: BASE, auth: auth().auth, reference: REF, fetch: script.impl, onChanged: () => {} })
    await settle()
    expect(script.connections).toHaveLength(1)

    script.ends[0]!()
    await settle()
    vi.advanceTimersByTime(999)
    await settle()
    expect(script.connections, 'no reconnect before the first second').toHaveLength(1)

    vi.advanceTimersByTime(1)
    await settle()
    expect(script.connections).toHaveLength(2)
    expect(script.connections[1]!.headers.get('Last-Event-ID')).toBe('9')
  })

  it('backs off exponentially from one second and caps the wait at thirty seconds', async () => {
    const script = scriptedFetch(Array.from({ length: 7 }, () => new TypeError('network down')))
    openCaseEvents({ baseUrl: BASE, auth: auth().auth, reference: REF, fetch: script.impl, onChanged: () => {} })
    await settle()

    for (const wait of [1000, 2000, 4000, 8000, 16000, 30000, 30000]) {
      const before = script.connections.length
      vi.advanceTimersByTime(wait - 1)
      await settle()
      expect(script.connections.length, `no retry before ${wait} ms`).toBe(before)
      vi.advanceTimersByTime(1)
      await settle()
      expect(script.connections.length, `retry at ${wait} ms`).toBe(before + 1)
    }
  })

  it('reports reconnecting only after the second failed attempt in a row', async () => {
    const states: CaseEventsState[] = []
    const script = scriptedFetch([new TypeError('down'), new TypeError('down'), { chunks: [] }])
    openCaseEvents({ baseUrl: BASE, auth: auth().auth, reference: REF, fetch: script.impl, onChanged: () => {}, onState: (state) => states.push(state) })
    await settle()
    expect(states, 'one failure is not shown').not.toContain('reconnecting')

    vi.advanceTimersByTime(1000)
    await settle()
    expect(states).toContain('reconnecting')

    vi.advanceTimersByTime(2000)
    await settle()
    expect(states.at(-1)).toBe('open')
  })

  it('stops for good when the server replaces the stream', async () => {
    const script = scriptedFetch([{ chunks: ['event: replaced\ndata: {}\n\n'] }])
    openCaseEvents({ baseUrl: BASE, auth: auth().auth, reference: REF, fetch: script.impl, onChanged: () => {} })
    await settle()
    script.ends[0]!()
    vi.advanceTimersByTime(60_000)
    await settle()

    expect(script.connections).toHaveLength(1)
  })

  it('a forbidden answer tells the user once and does not retry', async () => {
    const { auth: httpAuth, events } = auth()
    const script = scriptedFetch([{ status: 403 }])
    openCaseEvents({ baseUrl: BASE, auth: httpAuth, reference: REF, fetch: script.impl, onChanged: () => {} })
    await settle()
    vi.advanceTimersByTime(60_000)
    await settle()

    expect(events.forbidden).toBe(1)
    expect(script.connections).toHaveLength(1)
  })

  it('an unknown case (404) stops without retrying', async () => {
    const script = scriptedFetch([{ status: 404 }])
    openCaseEvents({ baseUrl: BASE, auth: auth().auth, reference: REF, fetch: script.impl, onChanged: () => {} })
    await settle()
    vi.advanceTimersByTime(60_000)
    await settle()

    expect(script.connections).toHaveLength(1)
  })

  it('close aborts the open request and prevents any reconnect', async () => {
    const script = scriptedFetch([{ chunks: [] }, { chunks: [] }])
    const stream = openCaseEvents({ baseUrl: BASE, auth: auth().auth, reference: REF, fetch: script.impl, onChanged: () => {} })
    await settle()
    stream.close()
    script.ends[0]?.()
    vi.advanceTimersByTime(60_000)
    await settle()

    expect(script.connections).toHaveLength(1)
    expect(script.connections[0]!.signal.aborted).toBe(true)
  })
})
