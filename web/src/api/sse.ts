/** One Server-Sent Events frame; `event` is `message` when the frame names none. */
export interface SseFrame {
  event: string
  id: string | undefined
  data: string
  /** The last `retry:` value the stream sent, in milliseconds. */
  retry: number | undefined
}

export interface SseParser {
  /** Feeds the next piece of text; a frame is emitted once its blank line has arrived. */
  push(chunk: string): void
  /** The stream ended: a last frame without its blank line is emitted too. */
  end(): void
}

/**
 * A small parser for the `text/event-stream` format (lines, blank line between frames, `:` comments).
 * It is fed text, so the caller decodes bytes with a streaming TextDecoder and the parser works
 * whatever the chunk boundaries are.
 */
export function createSseParser(onFrame: (frame: SseFrame) => void): SseParser {
  let pending = ''
  let event: string | undefined
  let id: string | undefined
  let data: string[] = []
  let retry: number | undefined
  let skipLf = false

  function dispatch(): void {
    if (event !== undefined || data.length > 0) {
      onFrame({ event: event ?? 'message', id, data: data.join('\n'), retry })
    }
    event = undefined
    id = undefined
    data = []
  }

  function line(text: string): void {
    if (text === '') {
      dispatch()
      return
    }
    if (text.startsWith(':')) return
    const colon = text.indexOf(':')
    const field = colon === -1 ? text : text.slice(0, colon)
    let value = colon === -1 ? '' : text.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    switch (field) {
      case 'event':
        event = value
        break
      case 'data':
        data.push(value)
        break
      case 'id':
        if (!value.includes('\0')) id = value
        break
      case 'retry':
        if (/^\d+$/.test(value)) retry = Number(value)
        break
      default:
        break
    }
  }

  return {
    push(chunk) {
      let text = pending + chunk
      // A CR ending a chunk may be the first half of a CRLF: the LF that follows belongs to it.
      if (skipLf && text.startsWith('\n')) text = text.slice(1)
      skipLf = text.endsWith('\r')
      const lines = text.split(/\r\n|\r|\n/)
      pending = lines.pop() ?? ''
      for (const current of lines) line(current)
    },
    end() {
      if (pending !== '') line(pending)
      pending = ''
      dispatch()
    },
  }
}
