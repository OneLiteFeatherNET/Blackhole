import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PortalApi } from '../api/portal'
import { APPEAL_OPEN, entry, facts, mountPortal, ok, page, signedInApi } from '../test/helpers'

/**
 * The live stream and the poll cadence of the conversation. The stream is a stub: the test fires its
 * events itself and nothing connects. Only the interval is faked, so time moves when the test says so.
 */

type Listener = (event: MessageEvent<string>) => void

class StubEventSource {
  static instances: StubEventSource[] = []
  readyState = 0
  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  closed = false
  private listeners = new Map<string, Listener[]>()

  readonly url: string

  constructor(url: string) {
    this.url = url
    StubEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: Listener) {
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

let mounted: Awaited<ReturnType<typeof mountPortal>> | undefined

beforeEach(() => {
  StubEventSource.instances = []
  vi.stubGlobal('EventSource', StubEventSource)
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
})

afterEach(() => {
  mounted?.wrapper.unmount()
  mounted = undefined
  vi.useRealTimers()
  vi.unstubAllGlobals()
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
})

async function openConversation(methods: Partial<PortalApi> = {}) {
  const conversation = vi.fn(methods.conversation ?? (async () => ok(page([entry(1)]))))
  mounted = await mountPortal(signedInApi({ appeal: async () => ok(APPEAL_OPEN), ...methods, conversation }, facts({ appealState: 'OPEN' })))
  await mounted.wrapper.findAll('button').find((b) => b.text() === 'Open conversation')!.trigger('click')
  await flushPromises()
  return { conversation, session: mounted.session }
}

async function tick(ms: number) {
  vi.advanceTimersByTime(ms)
  await flushPromises()
}

describe('live stream', () => {
  it('opens the stream on the BFF for a full session', async () => {
    await openConversation()

    expect(StubEventSource.instances).toHaveLength(1)
    expect(StubEventSource.instances[0]!.url).toBe('/api/conversation/events')
  })

  it('polls every 30 seconds while the stream is open, not every 15', async () => {
    const { conversation } = await openConversation()
    StubEventSource.instances[0]!.emit('ready', '{"seq":1}')
    const before = conversation.mock.calls.length

    await tick(15_000)
    expect(conversation).toHaveBeenCalledTimes(before)
    await tick(15_000)
    expect(conversation).toHaveBeenCalledTimes(before + 1)
  })

  it('reads the conversation at once when the stream reports a change, with the cursor', async () => {
    const { conversation } = await openConversation()
    StubEventSource.instances[0]!.emit('ready', '{"seq":1}')
    const before = conversation.mock.calls.length

    StubEventSource.instances[0]!.emit('changed', '{"seq":2}')
    await flushPromises()

    expect(conversation).toHaveBeenCalledTimes(before + 1)
    expect(conversation).toHaveBeenLastCalledWith(1)
  })

  it('ends the session screen when the stream says the session expired', async () => {
    const { session } = await openConversation()

    StubEventSource.instances[0]!.emit('expired', '{}')
    await flushPromises()

    expect(session.state.phase).toBe('expired')
    expect(StubEventSource.instances[0]!.closed).toBe(true)
  })

  it('falls back to polling every 15 seconds when the browser refuses the stream', async () => {
    const { conversation } = await openConversation()
    const source = StubEventSource.instances[0]!
    const before = conversation.mock.calls.length

    source.readyState = 2
    source.onerror?.(new Event('error'))
    await flushPromises()
    await tick(15_000)

    expect(conversation.mock.calls.length).toBe(before + 1)
  })

  it('says it is reconnecting after two failed attempts, and not before', async () => {
    const { session } = await openConversation()
    const source = StubEventSource.instances[0]!
    void session

    source.readyState = 0
    source.onerror?.(new Event('error'))
    await flushPromises()
    expect(mounted!.wrapper.text()).not.toContain('Reconnecting')

    source.onerror?.(new Event('error'))
    await flushPromises()
    expect(mounted!.wrapper.text()).toContain('Reconnecting')
  })

  it('closes the stream when the page is left', async () => {
    await openConversation()
    const source = StubEventSource.instances[0]!

    mounted!.wrapper.unmount()
    mounted = undefined

    expect(source.closed).toBe(true)
  })

  it('does not poll while the tab is hidden, even beside a stream', async () => {
    const { conversation } = await openConversation()
    StubEventSource.instances[0]!.emit('ready', '{"seq":1}')
    const before = conversation.mock.calls.length
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()

    await tick(60_000)

    expect(conversation).toHaveBeenCalledTimes(before)
  })
})

describe('without a stream', () => {
  it('keeps the 15 second cadence when the environment has no EventSource', async () => {
    vi.stubGlobal('EventSource', undefined)
    const { conversation } = await openConversation()
    const before = conversation.mock.calls.length

    await tick(15_000)

    expect(conversation).toHaveBeenCalledTimes(before + 1)
  })
})
