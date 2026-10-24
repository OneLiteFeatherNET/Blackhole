import { describe, expect, it, vi } from 'vitest'
import { openLive, type LiveSource, type LiveState } from './live'

/** A stand-in for EventSource: the test fires the events itself, nothing connects anywhere. */
class FakeSource implements LiveSource {
  readyState = 0
  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  closed = false
  private listeners = new Map<string, Array<(event: MessageEvent<string>) => void>>()

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  emit(type: string, data = '{}') {
    for (const listener of this.listeners.get(type) ?? []) listener(new MessageEvent(type, { data }))
  }

  close() {
    this.closed = true
    this.readyState = 2
  }
}

function open() {
  const source = new FakeSource()
  const onChanged = vi.fn<(seq: number) => void>()
  const onExpired = vi.fn<() => void>()
  const states: LiveState[] = []
  const handle = openLive({
    url: '/api/conversation/events',
    createSource: () => source,
    onChanged,
    onExpired,
    onState: (state) => states.push(state),
  })
  return { source, onChanged, onExpired, states, handle }
}

describe('live signal', () => {
  it('reports the seq of a changed event and nothing else', () => {
    const { source, onChanged } = open()

    source.emit('changed', '{"seq":12}')

    expect(onChanged).toHaveBeenCalledTimes(1)
    expect(onChanged).toHaveBeenCalledWith(12)
  })

  it('ignores a changed event whose data is not a seq', () => {
    const { source, onChanged } = open()

    source.emit('changed', 'not json')
    source.emit('changed', '{"seq":"12"}')
    source.emit('changed', '{"seq":-1}')
    source.emit('changed', '{"text":"hello"}')

    expect(onChanged).not.toHaveBeenCalled()
  })

  it('is open after ready, and says so once', () => {
    const { source, states } = open()

    source.emit('ready', '{"seq":4}')

    expect(states).toEqual(['open'])
  })

  it('is open as soon as the connection opens', () => {
    const { source, states } = open()

    source.onopen?.(new Event('open'))

    expect(states).toEqual(['open'])
  })
})

describe('ending the stream', () => {
  it('closes and reports expiry on an expired event, once', () => {
    const { source, onExpired, states } = open()

    source.emit('expired', '{}')
    source.emit('expired', '{}')

    expect(source.closed).toBe(true)
    expect(onExpired).toHaveBeenCalledTimes(1)
    expect(states.at(-1)).toBe('closed')
  })

  it('closes without an expiry on a replaced event, and nothing reconnects it', () => {
    const { source, onExpired, states } = open()

    source.emit('replaced', '{}')
    source.onerror?.(new Event('error'))

    expect(source.closed).toBe(true)
    expect(onExpired).not.toHaveBeenCalled()
    expect(states.at(-1)).toBe('closed')
  })

  it('closes for good when the browser gives up (a refused answer)', () => {
    const { source, states } = open()

    source.readyState = 2
    source.onerror?.(new Event('error'))

    expect(source.closed).toBe(true)
    expect(states.at(-1)).toBe('closed')
  })

  it('stops reacting once the handle is closed', () => {
    const { source, handle, onChanged, states } = open()

    handle.close()
    source.emit('changed', '{"seq":3}')
    source.onopen?.(new Event('open'))

    expect(onChanged).not.toHaveBeenCalled()
    expect(states).toEqual(['closed'])
  })
})

describe('reconnecting', () => {
  it('leaves the browser to retry a dropped connection without a notice at first', () => {
    const { source, states } = open()

    source.readyState = 0
    source.onerror?.(new Event('error'))

    expect(source.closed).toBe(false)
    expect(states).toEqual([])
  })

  it('says it is reconnecting after the second failed attempt in a row', () => {
    const { source, states } = open()

    source.readyState = 0
    source.onerror?.(new Event('error'))
    source.onerror?.(new Event('error'))

    expect(states).toEqual(['reconnecting'])
  })

  it('goes back to open when a retry succeeds', () => {
    const { source, states } = open()

    source.readyState = 0
    source.onerror?.(new Event('error'))
    source.onerror?.(new Event('error'))
    source.onopen?.(new Event('open'))

    expect(states).toEqual(['reconnecting', 'open'])
  })
})
