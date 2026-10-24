import { describe, expect, it } from 'vitest'
import { createSseParser, type SseFrame } from './sse'

function parse(chunks: string[]): SseFrame[] {
  const frames: SseFrame[] = []
  const parser = createSseParser((frame) => frames.push(frame))
  for (const chunk of chunks) parser.push(chunk)
  parser.end()
  return frames
}

describe('createSseParser', () => {
  it('reads the event, id and data of a frame', () => {
    expect(parse(['event: changed\nid: 7\ndata: {"seq":7}\n\n'])).toEqual([{ event: 'changed', id: '7', data: '{"seq":7}', retry: undefined }])
  })

  it('joins several data lines with a newline', () => {
    expect(parse(['event: ready\ndata: a\ndata: b\n\n'])[0]!.data).toBe('a\nb')
  })

  it('ignores comment lines such as the keep-alive', () => {
    expect(parse([': ka\n\n: ka\n\nevent: changed\ndata: {}\n\n'])).toEqual([{ event: 'changed', id: undefined, data: '{}', retry: undefined }])
  })

  it('carries a retry line on the frame that follows it', () => {
    expect(parse(['retry: 5000\n\nevent: ready\ndata: {"seq":1}\n\n'])).toEqual([{ event: 'ready', id: undefined, data: '{"seq":1}', retry: 5000 }])
  })

  it('emits a frame only at its blank line, even when chunks split a line', () => {
    expect(parse(['event: chan', 'ged\nda', 'ta: {"seq":', '2}\n', '\n'])).toEqual([
      { event: 'changed', id: undefined, data: '{"seq":2}', retry: undefined },
    ])
  })

  it('accepts CRLF line endings', () => {
    expect(parse(['event: changed\r\ndata: {}\r\n\r\n'])).toEqual([{ event: 'changed', id: undefined, data: '{}', retry: undefined }])
  })

  it('emits nothing for a frame that has neither data nor a name', () => {
    expect(parse(['id: 3\n\n'])).toEqual([])
  })

  it('emits a last frame that the stream ends without a blank line', () => {
    expect(parse(['event: expired\ndata: {}'])).toEqual([{ event: 'expired', id: undefined, data: '{}', retry: undefined }])
  })
})
