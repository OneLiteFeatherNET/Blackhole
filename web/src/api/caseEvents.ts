import { API_VERSION, API_VERSION_HEADER, type HttpAuth } from './http'
import { createSseParser, type SseFrame } from './sse'

/** What the live connection is doing; `reconnecting` is shown only after a second failed attempt in a row. */
export type CaseEventsState = 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface CaseEventsOptions {
  /** Where the API is mounted, as for the other calls. */
  baseUrl: string
  auth: HttpAuth
  /** The punishment identifier or short id of the case. */
  reference: string
  /** A change the reader can see; the frame carries the seq only, the caller reads the case again. */
  onChanged: (seq: number) => void
  onReady?: (seq: number) => void
  onState?: (state: CaseEventsState) => void
  /** Only tests pass this; the default is the global fetch. */
  fetch?: typeof fetch
}

export interface CaseEvents {
  /** Stops the stream for good: the open request is aborted and nothing reconnects. */
  close(): void
}

const FIRST_WAIT_MS = 1000
const MAX_WAIT_MS = 30_000

/** The `seq` of a frame's JSON body, or null when the body does not carry one. */
function seqOf(frame: SseFrame): number | null {
  try {
    const body = JSON.parse(frame.data) as { seq?: unknown }
    return typeof body.seq === 'number' && Number.isSafeInteger(body.seq) ? body.seq : null
  } catch {
    return null
  }
}

/**
 * Keeps a live connection to the case's event stream. The panel's token is a Bearer header, which
 * `EventSource` cannot send, so this reads the stream with `fetch`: a fresh token on every connect,
 * `Last-Event-ID` on every reconnect, and an exponential back-off from one second to thirty.
 * A `replaced` frame, a 403 or a 404 stops it for good; anything else reconnects.
 */
export function openCaseEvents(options: CaseEventsOptions): CaseEvents {
  const send: typeof fetch = options.fetch ?? ((input, init) => globalThis.fetch(input, init))
  const url = `${options.baseUrl}/case/${encodeURIComponent(options.reference)}/events`

  let closed = false
  let stopped = false
  let failures = 0
  let lastId: string | undefined
  let controller: AbortController | null = null
  let timer: ReturnType<typeof setTimeout> | undefined

  const state = (value: CaseEventsState) => options.onState?.(value)

  function stop(value: CaseEventsState): void {
    stopped = true
    state(value)
  }

  function retryLater(): void {
    if (closed || stopped) return
    failures += 1
    if (failures >= 2) state('reconnecting')
    const wait = Math.min(MAX_WAIT_MS, FIRST_WAIT_MS * 2 ** (failures - 1))
    timer = setTimeout(() => void connect(), wait)
  }

  function handle(frame: SseFrame, current: AbortController): void {
    if (frame.id !== undefined) lastId = frame.id
    switch (frame.event) {
      case 'ready': {
        const seq = seqOf(frame)
        if (seq !== null) options.onReady?.(seq)
        break
      }
      case 'changed': {
        const seq = seqOf(frame)
        if (seq !== null) options.onChanged(seq)
        break
      }
      case 'replaced':
        // Another stream of this session took over; it must not fight with it.
        stopped = true
        current.abort()
        state('closed')
        break
      default:
        break
    }
  }

  async function connect(forcedToken?: string): Promise<void> {
    timer = undefined
    if (closed || stopped) return
    const current = new AbortController()
    controller = current
    state('connecting')

    const token = forcedToken ?? (await options.auth.getAccessToken()) ?? (await options.auth.renew())
    if (closed) return
    if (!token) {
      retryLater()
      return
    }

    const headers = new Headers({ Authorization: `Bearer ${token}`, Accept: 'text/event-stream', [API_VERSION_HEADER]: API_VERSION })
    if (lastId !== undefined) headers.set('Last-Event-ID', lastId)

    let response: Response
    try {
      response = await send(url, { headers, signal: current.signal })
    } catch {
      if (!closed) retryLater()
      return
    }
    if (closed) return

    if (response.status === 401) {
      // The token is refused: one renewal, then the sign-in starts again.
      const renewed = await options.auth.renew()
      if (closed) return
      if (!renewed) {
        options.auth.signIn()
        stop('closed')
        return
      }
      void connect(renewed)
      return
    }
    if (response.status === 403) {
      options.auth.forbidden()
      stop('closed')
      return
    }
    if (response.status === 404) {
      stop('closed')
      return
    }
    if (!response.ok || !response.body) {
      retryLater()
      return
    }

    failures = 0
    state('open')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    const parser = createSseParser((frame) => handle(frame, current))
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        parser.push(decoder.decode(value, { stream: true }))
      }
      parser.end()
    } catch {
      // Aborted by close or by a replacement, or the network dropped: the checks below decide.
    }
    if (closed || stopped) return
    retryLater()
  }

  void connect()

  return {
    close() {
      if (closed) return
      closed = true
      if (timer !== undefined) clearTimeout(timer)
      controller?.abort()
      state('closed')
    },
  }
}
