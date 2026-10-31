import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CaseEventsState } from '../../api/caseEvents'
import type { CaseTimelineItem, CaseWithSeen } from '../../api/types'
import { caseResponse, ME, timelineItem } from '../../test/cases'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import { dayLabel } from './conversation'
import ConversationSection from './ConversationSection.vue'

const FIRST = '3f2b8c1e-0000-4000-8000-000000000301'
const SECOND = '3f2b8c1e-0000-4000-8000-000000000302'
const THIRD = '3f2b8c1e-0000-4000-8000-000000000303'

/** A staff message at a fixed time; the calendar day comes from `occurredAt`, never from the clock. */
function staffMessage(seq: number, id: string, occurredAt: number): CaseTimelineItem {
  return timelineItem({
    seq,
    type: 'message.added',
    subjectId: id,
    occurredAt,
    visibility: 'PLAYER',
    actor: { oid: ME, name: 'Me Myself', trust: 'VERIFIED' },
    payload: { messageId: id, author: { kind: 'STAFF' }, channel: 'PANEL', text: `message ${seq}` },
  })
}

const JULY_1 = Date.UTC(2026, 6, 1, 12, 0)
const JULY_2 = Date.UTC(2026, 6, 2, 12, 0)

interface Harness {
  api: ReturnType<typeof mockApi>
  /** Replaces what the server returns for the thread; the next `announce` makes the page reload it. */
  setThread(events: CaseTimelineItem[]): void
  /** The event stream reports a change, as the server would. */
  announce(): void
}

function harness(initial: CaseTimelineItem[], extra: Record<string, unknown> = {}): Harness {
  let thread = initial
  let handlers: { onChanged?: () => void; onState?: (state: CaseEventsState) => void } = {}
  const api = mockApi({
    caseConversation: vi.fn(async () => ok(thread)),
    caseDetail: vi.fn(async () => ok(caseResponse())),
    openCaseEvents: vi.fn((_reference: string, next: typeof handlers) => {
      handlers = next
      return { close() {} }
    }),
    ...extra,
  })
  return {
    api,
    setThread: (events) => (thread = events),
    announce: () => handlers.onChanged?.(),
  }
}

async function mount(options: { events: CaseTimelineItem[]; active?: boolean; roles?: string[]; extra?: Record<string, unknown> }) {
  const h = harness(options.events, options.extra)
  const mounted = await mountWithApp(ConversationSection, {
    api: h.api,
    roles: options.roles,
    props: { kase: caseResponse() as CaseWithSeen, active: options.active ?? true },
  })
  return { ...mounted, h }
}

/** Gives the thread element a size and a scroll position; jsdom has no layout. */
function stubScroll(element: HTMLElement, metrics: { scrollHeight: number; clientHeight: number; scrollTop: number }): void {
  Object.defineProperty(element, 'scrollHeight', { configurable: true, value: metrics.scrollHeight })
  Object.defineProperty(element, 'clientHeight', { configurable: true, value: metrics.clientHeight })
  Object.defineProperty(element, 'scrollTop', { configurable: true, writable: true, value: metrics.scrollTop })
}

const threadOf = (wrapper: VueWrapper): HTMLElement => wrapper.get('[data-testid="thread"]').element as HTMLElement

describe('ConversationSection: following the newest message', () => {
  it('scrolls to a new message when the reader is at the end of the thread', async () => {
    const { wrapper, h } = await mount({ events: [staffMessage(1, FIRST, JULY_1)] })
    stubScroll(threadOf(wrapper), { scrollHeight: 1000, clientHeight: 400, scrollTop: 600 })

    h.setThread([staffMessage(1, FIRST, JULY_1), staffMessage(2, SECOND, JULY_1)])
    h.announce()
    await flushPromises()

    expect(threadOf(wrapper).scrollTop).toBe(1000)

    wrapper.unmount()
  })

  it('keeps the place of a reader who scrolled up', async () => {
    const { wrapper, h } = await mount({ events: [staffMessage(1, FIRST, JULY_1)] })
    stubScroll(threadOf(wrapper), { scrollHeight: 1000, clientHeight: 400, scrollTop: 100 })

    h.setThread([staffMessage(1, FIRST, JULY_1), staffMessage(2, SECOND, JULY_1)])
    h.announce()
    await flushPromises()

    expect(threadOf(wrapper).scrollTop).toBe(100)

    wrapper.unmount()
  })

  it('counts a reader 48 px above the end as following the thread', async () => {
    const { wrapper, h } = await mount({ events: [staffMessage(1, FIRST, JULY_1)] })
    stubScroll(threadOf(wrapper), { scrollHeight: 1000, clientHeight: 400, scrollTop: 552 })

    h.setThread([staffMessage(1, FIRST, JULY_1), staffMessage(2, SECOND, JULY_1)])
    h.announce()
    await flushPromises()

    expect(threadOf(wrapper).scrollTop).toBe(1000)

    wrapper.unmount()
  })

  it('counts a reader 49 px above the end as scrolled up', async () => {
    const { wrapper, h } = await mount({ events: [staffMessage(1, FIRST, JULY_1)] })
    stubScroll(threadOf(wrapper), { scrollHeight: 1000, clientHeight: 400, scrollTop: 551 })

    h.setThread([staffMessage(1, FIRST, JULY_1), staffMessage(2, SECOND, JULY_1)])
    h.announce()
    await flushPromises()

    expect(threadOf(wrapper).scrollTop).toBe(551)

    wrapper.unmount()
  })

  it('scrolls to the end when its tab becomes visible, because a hidden thread has no size', async () => {
    const { wrapper } = await mount({ events: [staffMessage(1, FIRST, JULY_1)], active: false })
    stubScroll(threadOf(wrapper), { scrollHeight: 1000, clientHeight: 400, scrollTop: 0 })

    await wrapper.setProps({ active: true })
    await flushPromises()

    expect(threadOf(wrapper).scrollTop).toBe(1000)

    wrapper.unmount()
  })
})

describe('ConversationSection: the sender after a reply', () => {
  it('lands the moderator on their reply when they had scrolled up', async () => {
    const reply = staffMessage(2, SECOND, JULY_1)
    const { wrapper, h } = await mount({
      events: [staffMessage(1, FIRST, JULY_1)],
      roles: ['MODERATOR', 'VIEWER'],
      extra: { postCaseMessage: vi.fn(async () => ok({ messageId: SECOND, seq: 2, repeated: false })) },
    })
    stubScroll(threadOf(wrapper), { scrollHeight: 1000, clientHeight: 400, scrollTop: 100 })

    h.setThread([staffMessage(1, FIRST, JULY_1), reply])
    await wrapper.get('[data-testid="message-text"]').setValue('Thanks, we checked.')
    await wrapper.get('[data-testid="message-composer"]').trigger('submit')
    await flushPromises()

    expect(threadOf(wrapper).scrollTop).toBe(1000)

    wrapper.unmount()
  })
})

describe('ConversationSection: reporting the message count', () => {
  it('reports the count of the loaded thread once, not as new messages', async () => {
    const { wrapper } = await mount({ events: [staffMessage(1, FIRST, JULY_1), staffMessage(2, SECOND, JULY_1)] })

    expect(wrapper.emitted('messages')!.map((args) => args[0])).toEqual([2])

    wrapper.unmount()
  })

  it('reports the count again when a message arrives', async () => {
    const { wrapper, h } = await mount({ events: [staffMessage(1, FIRST, JULY_1)] })

    h.setThread([staffMessage(1, FIRST, JULY_1), staffMessage(2, SECOND, JULY_1)])
    h.announce()
    await flushPromises()

    expect(wrapper.emitted('messages')!.map((args) => args[0])).toEqual([1, 2])

    wrapper.unmount()
  })

  it('reports an empty thread as zero, so the first message later counts as new', async () => {
    const { wrapper, h } = await mount({ events: [] })

    expect(wrapper.emitted('messages')!.map((args) => args[0])).toEqual([0])

    h.setThread([staffMessage(1, FIRST, JULY_1)])
    h.announce()
    await flushPromises()

    expect(wrapper.emitted('messages')!.map((args) => args[0])).toEqual([0, 1])

    wrapper.unmount()
  })
})

describe('ConversationSection: days', () => {
  it('puts one divider before the first message of each calendar day', async () => {
    const { wrapper } = await mount({
      events: [staffMessage(1, FIRST, JULY_1), staffMessage(2, SECOND, JULY_1 + 60_000), staffMessage(3, THIRD, JULY_2)],
    })

    const dividers = wrapper.findAll('[data-testid="day-divider"]')
    expect(dividers.map((divider) => divider.text())).toEqual([dayLabel(JULY_1, 'en'), dayLabel(JULY_2, 'en')])
    expect(wrapper.findAll('[data-testid="thread"] > li[data-author]'), 'the messages are not dividers').toHaveLength(3)

    wrapper.unmount()
  })
})

describe('ConversationSection: messenger layout', () => {
  it('scrolls the thread in its own area, with the reply composer below it', async () => {
    const { wrapper } = await mount({ events: [staffMessage(1, FIRST, JULY_1)] })

    const thread = wrapper.get('[data-testid="thread"]')
    expect(thread.classes()).toContain('overflow-y-auto')
    expect(thread.classes()).toContain('h-[60dvh]')
    const composer = wrapper.get('[data-testid="message-composer"]').element
    expect(thread.element.compareDocumentPosition(composer) & Node.DOCUMENT_POSITION_FOLLOWING, 'the composer follows the thread').not.toBe(0)

    wrapper.unmount()
  })

  it('shows no ticket strip when the case has no tickets', async () => {
    const { wrapper } = await mount({ events: [staffMessage(1, FIRST, JULY_1)] })

    expect(wrapper.find('[data-testid="conversation-strip"]').exists(), 'no empty box above the thread').toBe(false)

    wrapper.unmount()
  })

  it('shows the ticket strip above the thread when the case has a ticket', async () => {
    const opened = timelineItem({ seq: 2, type: 'ticket.opened', payload: { ticketId: 'ticket-1', appealId: 'appeal-1', ticketNo: 12 } })
    const { wrapper } = await mount({ events: [opened, staffMessage(1, FIRST, JULY_1)] })

    const strip = wrapper.get('[data-testid="conversation-strip"]').element
    expect(wrapper.get('[data-testid="ticket-12"]').element.closest('[data-testid="conversation-strip"]')).toBe(strip)
    expect(strip.compareDocumentPosition(threadOf(wrapper)) & Node.DOCUMENT_POSITION_FOLLOWING, 'the thread follows the strip').not.toBe(0)

    wrapper.unmount()
  })

  it('collapses the admin erasure tools below the conversation', async () => {
    const player = timelineItem({ seq: 9, type: 'message.added', subjectId: THIRD, payload: { messageId: THIRD, author: { kind: 'PLAYER' }, channel: 'PORTAL', text: 'hello' }, actor: { trust: 'PLAYER' } })
    const { wrapper } = await mount({ events: [player], roles: ['ADMIN', 'MODERATOR', 'VIEWER'] })

    const tools = wrapper.get('[data-testid="admin-tools"]')
    expect(tools.element.tagName).toBe('DETAILS')
    expect(tools.element.hasAttribute('open'), 'collapsed by default').toBe(false)
    expect(tools.find('[data-testid="erase-player"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows no admin tools to a moderator', async () => {
    const { wrapper } = await mount({ events: [staffMessage(1, FIRST, JULY_1)], roles: ['MODERATOR', 'VIEWER'] })

    expect(wrapper.find('[data-testid="admin-tools"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
