// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { limitBytes, relayBody, TooLargeError } from './streams'

/** A source of the given chunks that records when it is cancelled. No timers, no network. */
function source(chunks: Uint8Array[]) {
  const state = { cancelled: false, reason: undefined as unknown }
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk)
      controller.close()
    },
    cancel(reason) {
      state.cancelled = true
      state.reason = reason
    },
  })
  return { stream, state }
}

async function readAll(stream: ReadableStream<Uint8Array>): Promise<number[][]> {
  const reader = stream.getReader()
  const chunks: number[][] = []
  for (;;) {
    const { done, value } = await reader.read()
    if (done) return chunks
    chunks.push([...value])
  }
}

describe('limitBytes', () => {
  it('passes every chunk through unchanged when the total is within the limit', async () => {
    const { stream } = source([new Uint8Array([1, 2]), new Uint8Array([3])])

    expect(await readAll(limitBytes(stream, 3))).toEqual([[1, 2], [3]])
  })

  it('errors the stream with TooLargeError once more bytes than the limit have gone by', async () => {
    const { stream } = source([new Uint8Array([1, 2]), new Uint8Array([3, 4])])

    await expect(readAll(limitBytes(stream, 3))).rejects.toBeInstanceOf(TooLargeError)
  })

  it('cancels the source when the limit is exceeded, so nothing more is read', async () => {
    const { stream, state } = source([new Uint8Array(4), new Uint8Array(4)])

    await expect(readAll(limitBytes(stream, 3))).rejects.toBeInstanceOf(TooLargeError)

    expect(state.cancelled).toBe(true)
  })

  it('passes a cancel of the output on to the source', async () => {
    const { stream, state } = source([new Uint8Array([1]), new Uint8Array([2])])
    const limited = limitBytes(stream, 10)

    await limited.cancel('stop')

    expect(state.cancelled).toBe(true)
    expect(state.reason).toBe('stop')
  })
})

describe('relayBody', () => {
  it('relays the chunks in order, one read per chunk', async () => {
    const { stream } = source([new Uint8Array([1]), new Uint8Array([2, 3])])

    expect(await readAll(relayBody(stream, new AbortController()))).toEqual([[1], [2, 3]])
  })

  it('aborts the upstream request and cancels the source when the consumer cancels', async () => {
    const { stream, state } = source([new Uint8Array([1]), new Uint8Array([2])])
    const abort = new AbortController()
    const relayed = relayBody(stream, abort)

    const reader = relayed.getReader()
    await reader.read()
    await reader.cancel('gone')

    expect(abort.signal.aborted).toBe(true)
    expect(state.cancelled).toBe(true)
  })

  it('does not abort while the consumer keeps reading', async () => {
    const { stream } = source([new Uint8Array([1])])
    const abort = new AbortController()

    await readAll(relayBody(stream, abort))

    expect(abort.signal.aborted).toBe(false)
  })
})
