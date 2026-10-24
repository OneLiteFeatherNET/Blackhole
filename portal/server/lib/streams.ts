/**
 * Byte streams for the BFF. Nothing here buffers a whole body: uploads pass through with a cap,
 * downloads and the live stream are relayed chunk by chunk, and a browser that goes away cancels
 * the upstream read so the backend request ends with it.
 */

export class TooLargeError extends Error {
  constructor() {
    super('stream exceeds its byte limit')
    this.name = 'TooLargeError'
  }
}

/**
 * Passes the chunks through unchanged and errors the stream as soon as more than `limit` bytes
 * have gone by. The check runs per chunk, so an oversized upload is cut off before it is read to the end.
 */
export function limitBytes(source: ReadableStream<Uint8Array>, limit: number): ReadableStream<Uint8Array> {
  const reader = source.getReader()
  let seen = 0
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      const { done, value } = await reader.read()
      if (done) {
        controller.close()
        return
      }
      seen += value.byteLength
      if (seen > limit) {
        const error = new TooLargeError()
        await reader.cancel(error).catch(() => undefined)
        controller.error(error)
        return
      }
      controller.enqueue(value)
    },
    cancel(reason) {
      return reader.cancel(reason)
    },
  })
}

/**
 * Relays an upstream body chunk by chunk, each chunk as soon as it arrives (no buffering, so an event
 * reaches the browser at once). When the consumer cancels, the upstream read is cancelled and the
 * abort controller of the upstream request is fired.
 */
export function relayBody(upstream: ReadableStream<Uint8Array>, abort: AbortController): ReadableStream<Uint8Array> {
  const reader = upstream.getReader()
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      const { done, value } = await reader.read()
      if (done) controller.close()
      else controller.enqueue(value)
    },
    cancel(reason) {
      abort.abort()
      return reader.cancel(reason)
    },
  })
}
