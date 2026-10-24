import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CaseEventsState } from '../../api/caseEvents'
import type { CaseTimelineItem, CaseWithSeen } from '../../api/types'
import { caseResponse, ME, timelineItem } from '../../test/cases'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import ConversationSection from './ConversationSection.vue'

const PLAYER_MSG = '3f2b8c1e-0000-4000-8000-000000000101'
const MINE = '3f2b8c1e-0000-4000-8000-000000000102'
const THEIRS = '3f2b8c1e-0000-4000-8000-000000000103'

function staffMessage(seq: number, id: string, text: string, extra: Partial<CaseTimelineItem> = {}) {
  return timelineItem({
    seq,
    type: 'message.added',
    subjectId: id,
    visibility: 'PLAYER',
    actor: { oid: ME, name: 'Me Myself', trust: 'VERIFIED' },
    payload: { messageId: id, author: { kind: 'STAFF' }, channel: 'PANEL', text },
    ...extra,
  })
}

function playerMessage(seq: number, id: string, text: string, payload: Record<string, unknown> = {}, extra: Partial<CaseTimelineItem> = {}) {
  return timelineItem({
    seq,
    type: 'message.added',
    subjectId: id,
    visibility: 'PLAYER',
    actor: { trust: 'PLAYER' },
    payload: { messageId: id, author: { kind: 'PLAYER' }, channel: 'PORTAL', text, ...payload },
    ...extra,
  })
}

interface Harness {
  api: Record<string, unknown>
  handlers: { onChanged?: (seq: number) => void; onState?: (state: CaseEventsState) => void }
  close: ReturnType<typeof vi.fn>
  openCaseEvents: ReturnType<typeof vi.fn>
}

function harness(overrides: Record<string, unknown> = {}, events: CaseTimelineItem[] = []): Harness {
  const close = vi.fn()
  const harnessState = { handlers: {} as Harness['handlers'] }
  const openCaseEvents = vi.fn((_reference: string, handlers: Harness['handlers']) => {
    harnessState.handlers = handlers
    return { close }
  })
  const api = {
    caseConversation: vi.fn(async () => ok(events)),
    caseDetail: vi.fn(async () => ok(caseResponse())),
    openCaseEvents,
    ...overrides,
  }
  return {
    api,
    get handlers() {
      return harnessState.handlers
    },
    close,
    openCaseEvents,
  }
}

async function mount(options: { roles?: string[]; events?: CaseTimelineItem[]; kase?: CaseWithSeen; api: Harness }) {
  return mountWithApp(ConversationSection, {
    api: mockApi(options.api.api),
    roles: options.roles,
    props: { kase: options.kase ?? (caseResponse() as CaseWithSeen) },
  })
}

describe('ConversationSection: chat bubbles and Markdown', () => {
  it('puts the player on the start side and staff on the end side', async () => {
    const h = harness({}, [playerMessage(1, PLAYER_MSG, 'Please look'), staffMessage(2, MINE, 'We will')])
    const { wrapper } = await mount({ api: h })

    expect(wrapper.get(`[data-testid="message-${PLAYER_MSG}"]`).attributes('data-side')).toBe('start')
    expect(wrapper.get(`[data-testid="message-${MINE}"]`).attributes('data-side')).toBe('end')
    wrapper.unmount()
  })

  it('draws the Markdown of a message from its block tree, not from its raw text', async () => {
    const blocks = [{ type: 'paragraph', children: [{ type: 'strong', children: [{ type: 'text', text: 'bold' }] }] }] as unknown as CaseTimelineItem['blocks']
    const h = harness({}, [staffMessage(1, MINE, '**bold**', { blocks })])
    const { wrapper } = await mount({ api: h })

    const view = wrapper.get(`[data-testid="message-${MINE}"] [data-testid="message-text-view"]`)
    expect(view.get('strong').text()).toBe('bold')
    expect(view.text()).not.toContain('**')
    wrapper.unmount()
  })

  it('draws hostile text in a message as text, with no element created from it', async () => {
    const hostile = '<img src=x onerror="alert(1)"><script>alert(2)</script>'
    const h = harness({}, [playerMessage(1, PLAYER_MSG, hostile, {}, {})])
    const { wrapper } = await mount({ api: h })

    expect(wrapper.get(`[data-testid="message-${PLAYER_MSG}"] [data-testid="message-text-view"]`).text()).toBe(hostile)
    expect(wrapper.element.querySelectorAll('img, script').length).toBe(0)
    wrapper.unmount()
  })

  it('lists the chat files of a player message with the player-supplied label', async () => {
    const files = [{ attachmentId: 'f-1', name: 'proof.png', contentType: 'image/png', size: 2048, provenance: 'PLAYER' }]
    const h = harness({}, [playerMessage(1, PLAYER_MSG, 'see the file', { attachments: files })])
    const { wrapper } = await mount({ api: h })

    const file = wrapper.get('[data-testid="file"]')
    expect(file.text()).toContain('proof.png')
    expect(file.text()).toContain('2 KB')
    expect(file.get('[data-testid="file-provenance"]').text()).toBe('Player-supplied, unverified')
    wrapper.unmount()
  })

  it('shows a share and a withdrawal in the thread as lines that carry no content', async () => {
    const events = [
      staffMessage(1, MINE, 'before'),
      timelineItem({ seq: 2, type: 'evidence.shared', payload: { shareId: 's-1', evidenceKind: 'CHAT_LOG', mode: 'EXCERPT', lineCount: 5 } }),
      timelineItem({ seq: 3, type: 'evidence.share_revoked', payload: { shareId: 's-1', reason: 'PRIVACY' } }),
    ]
    const h = harness({}, events)
    const { wrapper } = await mount({ api: h })

    const markers = wrapper.findAll('[data-testid="marker"]')
    expect(markers.map((marker) => marker.attributes('data-kind'))).toEqual(['SHARED', 'WITHDRAWN'])
    expect(markers[0]!.text()).toBe('Evidence shared with the player: chat excerpt, 5 lines')
    expect(markers[1]!.text()).toBe('Evidence share withdrawn')
    expect(wrapper.text()).not.toContain('PRIVACY')
    wrapper.unmount()
  })
})

describe('ConversationSection: seen marker', () => {
  it('marks the last staff message the player has read as Seen', async () => {
    const events = [playerMessage(1, PLAYER_MSG, 'hello'), staffMessage(2, MINE, 'answer'), staffMessage(4, THEIRS, 'later')]
    const h = harness({}, events)
    const { wrapper } = await mount({ api: h, kase: { ...caseResponse(), playerSeenSeq: 2 } as CaseWithSeen })

    const seen = wrapper.findAll('[data-testid="seen"]')
    expect(seen).toHaveLength(1)
    expect(seen[0]!.element.previousElementSibling?.getAttribute('data-testid')).toBe(`message-${MINE}`)
    wrapper.unmount()
  })

  it('shows no Seen when the player has read nothing from staff', async () => {
    const h = harness({}, [staffMessage(2, MINE, 'answer')])
    const { wrapper } = await mount({ api: h, kase: { ...caseResponse(), playerSeenSeq: 0 } as CaseWithSeen })

    expect(wrapper.find('[data-testid="seen"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('ConversationSection: live updates', () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] }))
  afterEach(() => vi.useRealTimers())

  it('follows the case event stream of the reference and closes it when the section goes', async () => {
    const h = harness()
    const { wrapper } = await mount({ api: h })

    expect(h.openCaseEvents).toHaveBeenCalledWith('p-0001', expect.objectContaining({ onChanged: expect.any(Function) }))
    wrapper.unmount()
    expect(h.close).toHaveBeenCalledTimes(1)
  })

  it('reads the conversation and the case again when the stream reports a change', async () => {
    const h = harness()
    const { wrapper } = await mount({ api: h })
    const before = (h.api.caseConversation as ReturnType<typeof vi.fn>).mock.calls.length

    h.handlers.onChanged?.(9)
    await flushPromises()

    expect((h.api.caseConversation as ReturnType<typeof vi.fn>).mock.calls.length).toBe(before + 1)
    expect(h.api.caseDetail).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('updates Seen from the case when a change arrives', async () => {
    const events = [staffMessage(2, MINE, 'answer')]
    const h = harness({ caseDetail: vi.fn(async () => ok({ ...caseResponse(), playerSeenSeq: 2 })) }, events)
    const { wrapper } = await mount({ api: h })
    expect(wrapper.find('[data-testid="seen"]').exists()).toBe(false)

    h.handlers.onChanged?.(10)
    await flushPromises()

    expect(wrapper.find('[data-testid="seen"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('polls every 30 seconds while the stream is open', async () => {
    const h = harness()
    const { wrapper } = await mount({ api: h })
    const reads = () => (h.api.caseConversation as ReturnType<typeof vi.fn>).mock.calls.length

    h.handlers.onState?.('open')
    await flushPromises()
    const before = reads()

    vi.advanceTimersByTime(29_999)
    await flushPromises()
    expect(reads()).toBe(before)

    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(reads()).toBe(before + 1)
    wrapper.unmount()
  })

  it('polls every 15 seconds without a stream, and says so when the stream is paused', async () => {
    const h = harness()
    const { wrapper } = await mount({ api: h })
    const reads = () => (h.api.caseConversation as ReturnType<typeof vi.fn>).mock.calls.length

    h.handlers.onState?.('reconnecting')
    await flushPromises()
    expect(wrapper.get('[data-testid="live-paused"]').text()).toContain('reconnect')
    const before = reads()

    vi.advanceTimersByTime(15_000)
    await flushPromises()
    expect(reads()).toBe(before + 1)
    wrapper.unmount()
  })
})

describe('ConversationSection: the composer by role', () => {
  it('offers the file controls to a moderator', async () => {
    const h = harness()
    const { wrapper } = await mount({ api: h, roles: ['MODERATOR'] })

    expect(wrapper.find('[data-testid="attach"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('offers no reply and no file controls to a viewer, who still follows the stream', async () => {
    const h = harness()
    const { wrapper } = await mount({ api: h, roles: ['VIEWER'] })

    expect(wrapper.find('[data-testid="attach"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="message-composer"]').exists()).toBe(false)
    expect(h.openCaseEvents).toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('ConversationSection: sending', () => {
  it('posts a reply with its staged files and reads the thread again', async () => {
    const postCaseMessage = vi.fn(async () => ok({ messageId: 'm', seq: 3, repeated: false }))
    const h = harness({ postCaseMessage, uploadCaseAttachment: vi.fn(async () => ok({ attachmentId: 'a-1', name: 'x.png', contentType: 'image/png', size: 1 })) })
    const { wrapper } = await mount({ api: h, roles: ['MODERATOR'] })
    const input = wrapper.get('[data-testid="file-input"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'x.png', { type: 'image/png' })], configurable: true })
    await input.trigger('change')
    await flushPromises()

    await wrapper.get('[data-testid="message-submit"]').trigger('click')
    await flushPromises()

    expect(postCaseMessage).toHaveBeenCalledWith('p-0001', '', expect.any(String), ['a-1'])
    wrapper.unmount()
  })
})
