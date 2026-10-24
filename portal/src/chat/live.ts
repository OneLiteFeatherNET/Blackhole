/**
 * The live signal of the conversation: an `EventSource` on the BFF that says only "something changed,
 * this is the seq". The page reads the change itself with its cursor, so no content ever travels here.
 * Reconnects are the browser's (the server sends `retry: 5000`); a refused stream is closed for good and
 * the page falls back to polling.
 */

/** The part of `EventSource` this module uses; a test passes a fake. */
export interface LiveSource {
  readyState: number
  onopen: ((event: Event) => void) | null
  onerror: ((event: Event) => void) | null
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void
  close(): void
}

export type LiveSourceFactory = (url: string) => LiveSource

export type LiveState = 'open' | 'reconnecting' | 'closed'

export interface LiveOptions {
  url: string
  createSource: LiveSourceFactory
  /** A change the reader may see; `seq` is the case's seq after it. */
  onChanged(seq: number): void
  /** The session ended (`expired`): the page leaves the conversation. */
  onExpired(): void
  onState(state: LiveState): void
}

export interface LiveHandle {
  close(): void
}

/** `EventSource.CLOSED`. */
const CLOSED = 2
/** Failed attempts in a row before the page says it is reconnecting. */
const FAILURES_BEFORE_NOTICE = 2

function seqOf(event: MessageEvent<string>): number | undefined {
  try {
    const data = JSON.parse(event.data) as unknown
    if (typeof data !== 'object' || data === null) return undefined
    const seq = (data as { seq?: unknown }).seq
    return typeof seq === 'number' && Number.isSafeInteger(seq) && seq >= 0 ? seq : undefined
  } catch {
    return undefined
  }
}

/** Opens the stream. Only the seq of a `changed` event is read; any other data is ignored. */
export function openLive(options: LiveOptions): LiveHandle {
  let failures = 0
  let closed = false
  const source = options.createSource(options.url)

  function close() {
    if (closed) return
    closed = true
    source.close()
    options.onState('closed')
  }

  source.onopen = () => {
    failures = 0
    if (!closed) options.onState('open')
  }

  source.addEventListener('ready', () => {
    failures = 0
    if (!closed) options.onState('open')
  })

  source.addEventListener('changed', (event) => {
    const seq = seqOf(event)
    if (!closed && seq !== undefined) options.onChanged(seq)
  })

  source.addEventListener('expired', () => {
    if (closed) return
    close()
    options.onExpired()
  })

  // Another stream of the same session took over: this one stops, and nothing reconnects it.
  source.addEventListener('replaced', () => close())

  source.onerror = () => {
    if (closed) return
    if (source.readyState === CLOSED) {
      // The browser gave up (a refused answer such as 401, 403 or 503): no retry, poll instead.
      close()
      return
    }
    failures += 1
    if (failures >= FAILURES_BEFORE_NOTICE) options.onState('reconnecting')
  }

  return { close }
}
