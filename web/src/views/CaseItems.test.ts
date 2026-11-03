import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { RouterView } from 'vue-router'
import { createBlackholeApi } from '../api/blackhole'
import type { CaseProtocolVersion, CaseTimelineItem } from '../api/types'
import { CLIPBOARD_KEY, type ClipboardWriter } from '../components/copyLink'
import { fakeHttpAuth } from '../test/auth'
import { caseResponse, ME, NOTE, timelineItem, timelinePage } from '../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import { chooseOption } from '../test/select'
import { stubReducedMotion } from '../test/reducedMotion'

/*
 * The items of a case as routes and addresses (`restructure-staff-panel`, decision 7 rows 2 to 5): the note, message and
 * protocol child routes of the case page, the `#event-<id>` highlight, and the evidence item page. The page is mounted
 * through the router, so each address is the one the panel would open.
 */

const MINE = '3f2b8c1e-0000-4000-8000-000000000022'
const PLAYER_MSG = '3f2b8c1e-0000-4000-8000-000000000021'
const EVIDENCE = '3f2b8c1e-0000-4000-8000-0000000000e2'
const OLDER_EVIDENCE = '3f2b8c1e-0000-4000-8000-0000000000e1'
const REFERENCE = 'p-0001'

const Shell = defineComponent({ setup: () => () => h(RouterView) })

const noteEvent = (seq: number, text: string, extra: Partial<CaseTimelineItem> = {}) =>
  timelineItem({ seq, type: 'note.added', subjectId: NOTE, payload: { noteId: NOTE, text }, actor: { oid: ME, name: 'Me', trust: 'VERIFIED' }, ...extra })

const staffMessage = (seq: number, id: string, text: string) =>
  timelineItem({
    seq,
    type: 'message.added',
    subjectId: id,
    visibility: 'PLAYER',
    actor: { oid: ME, name: 'Me', trust: 'VERIFIED' },
    payload: { messageId: id, author: { kind: 'STAFF' }, channel: 'PANEL', text },
  })

const playerMessage = (seq: number, id: string) =>
  timelineItem({
    seq,
    type: 'message.added',
    subjectId: id,
    visibility: 'PLAYER',
    actor: { trust: 'PLAYER' },
    payload: { messageId: id, author: { kind: 'PLAYER' }, channel: 'PORTAL', text: 'please look again' },
  })

/** The timeline answers the events of the requested groups, like the backend's type filter does. */
function filterByGroups(events: CaseTimelineItem[], types?: string[]): CaseTimelineItem[] {
  if (!types) return events
  return events.filter((event) => types.some((group) => event.type.startsWith(`${group}.`)))
}

interface SetupOptions {
  roles?: string[]
  path: string
  timeline?: CaseTimelineItem[]
  conversation?: CaseTimelineItem[]
  protocols?: CaseProtocolVersion[]
  extra?: Record<string, unknown>
  provide?: Record<string | symbol, unknown>
}

function setup(options: SetupOptions) {
  const timeline = options.timeline ?? [noteEvent(1, 'phone 555')]
  const api = {
    cases: vi.fn(async () => ok({ items: [], page: 0, size: 25, totalSize: 0, totalPages: 0 })),
    caseDetail: vi.fn(async () => ok(caseResponse())),
    caseTimeline: vi.fn(async (_reference: string, query?: { types?: string[] }) => ok(timelinePage(filterByGroups(timeline, query?.types)))),
    caseConversation: vi.fn(async () => ok(options.conversation ?? [])),
    caseProtocols: vi.fn(async () => ok(options.protocols ?? [])),
    caseDecidedAppeals: vi.fn(async () => ok([])),
    openCaseEvents: () => ({ close() {} }),
    addCaseNote: vi.fn(async () => ok({ noteId: NOTE, seq: 9 })),
    editCaseNote: vi.fn(async () => ok({ noteId: NOTE, seq: 10 })),
    redactCaseNote: vi.fn(async () => ok({ noteId: NOTE, redactedVersions: 1 })),
    editCaseMessage: vi.fn(async () => ok({ messageId: MINE, seq: 11, repeated: false })),
    redactCaseMessage: vi.fn(async () => ok({ messageId: MINE, redactedVersions: 1 })),
    openCaseProtocol: vi.fn(async () => ok({ appealId: 'a', version: 2, evidenceId: EVIDENCE, text: 'Player: hello\nStaff: hi' })),
    ...options.extra,
  }
  return {
    api,
    mount: (clipboard?: ClipboardWriter) =>
      mountWithApp(Shell, {
        api: mockApi(api),
        path: options.path,
        roles: options.roles,
        provide: { ...(clipboard ? { [CLIPBOARD_KEY as symbol]: clipboard } : {}), ...options.provide },
      }),
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

/** Stubs scrollIntoView (jsdom has none) so the scroll can be counted. */
function spyOnScroll() {
  const original = Element.prototype.scrollIntoView
  const scroll = vi.fn()
  Element.prototype.scrollIntoView = scroll as unknown as typeof original
  return { scroll, restore: () => (Element.prototype.scrollIntoView = original) }
}

describe('case notes: adding', () => {
  it('opens the new-note dialog from the audit tab and sends nothing', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: '/cases/p-0001?tab=notes-audit' })
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="open-note-new"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('case-note-new')
    expect(wrapper.find('[data-testid="note-editor"]').exists()).toBe(true)
    expect(api.addCaseNote).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('saves the note, closes the dialog and says so', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: '/cases/p-0001/notes/new' })
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="note-text"]').setValue('looked at the logs')
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    await flushPromises()

    expect(api.addCaseNote).toHaveBeenCalledWith(REFERENCE, 'looked at the logs')
    expect(router.currentRoute.value.name).toBe('case')
    expect(wrapper.findAll('[role="status"][aria-live="polite"]').map((live) => live.text())).toContain('Note saved.')

    wrapper.unmount()
  })

  it('keeps the dialog open with the server message when the note is refused', async () => {
    const addCaseNote = vi.fn(async () => fail('a note must not contain a UUID or an IP address', 400))
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: '/cases/p-0001/notes/new', extra: { addCaseNote } })
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="note-text"]').setValue('player 8.8.8.8')
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="note-error"]').text()).toContain('UUID or an IP address')
    expect(router.currentRoute.value.name).toBe('case-note-new')

    wrapper.unmount()
  })
})

describe('case notes: editing', () => {
  it('shows the latest text of the note in the edit dialog and sends nothing on open', async () => {
    const timeline = [noteEvent(1, 'first'), noteEvent(2, 'latest', { type: 'note.edited' })]
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: `/cases/p-0001/notes/${NOTE}/edit`, timeline })
    const { wrapper } = await mount()

    expect((wrapper.get('[data-testid="note-text"]').element as HTMLTextAreaElement).value).toBe('latest')
    expect(api.caseTimeline).toHaveBeenCalledWith(REFERENCE, expect.objectContaining({ types: ['note'] }))
    expect(api.editCaseNote).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('saves the edit with the note id and closes the dialog', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: `/cases/p-0001/notes/${NOTE}/edit` })
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="note-text"]').setValue('corrected')
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    await flushPromises()

    expect(api.editCaseNote).toHaveBeenCalledWith(REFERENCE, NOTE, 'corrected')
    expect(router.currentRoute.value.name).toBe('case')

    wrapper.unmount()
  })

  it('shows not-found and no form for a note that is not in the case', async () => {
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: '/cases/p-0001/notes/3f2b8c1e-0000-4000-8000-0000000000ff/edit' })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="note-text"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="routed-dialog-back"]').text()).toContain('Back to the page')

    wrapper.unmount()
  })

  it('treats a redacted note as missing: no text is shown and nothing can be edited', async () => {
    const timeline = [noteEvent(1, 'phone 555', { redactedAt: 1, payload: {} })]
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: `/cases/p-0001/notes/${NOTE}/edit`, timeline })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="note-text"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('phone 555')

    wrapper.unmount()
  })
})

describe('case notes: removing the text', () => {
  it('opens the redaction dialog and removes nothing until a reason is chosen and confirmed', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'], path: `/cases/p-0001/notes/${NOTE}/redact` })
    const { wrapper, router } = await mount()

    expect(api.redactCaseNote).not.toHaveBeenCalled()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'PERSONAL_DATA')
    await wrapper.get('[data-testid="redact-confirm"]').trigger('click')
    await flushPromises()

    expect(api.redactCaseNote).toHaveBeenCalledWith(REFERENCE, NOTE, 'PERSONAL_DATA')
    expect(router.currentRoute.value.name).toBe('case')

    wrapper.unmount()
  })

  it('shows not-found for a note that is not in the case', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'], path: '/cases/p-0001/notes/3f2b8c1e-0000-4000-8000-0000000000ff/redact' })
    const { wrapper } = await mount()

    expect(wrapper.find('[role="dialog"] [role="combobox"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="routed-dialog-back"]').exists()).toBe(true)
    expect(api.redactCaseNote).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('case messages', () => {
  const conversation = [staffMessage(2, MINE, 'We will'), playerMessage(1, PLAYER_MSG)]

  it('edits a staff message: its text is read first, then sent on save', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: `/cases/p-0001/messages/${MINE}/edit`, conversation })
    const { wrapper, router } = await mount()

    expect((wrapper.get('[role="dialog"] [data-testid="message-text"]').element as HTMLTextAreaElement).value).toBe('We will')
    await wrapper.get('[role="dialog"] [data-testid="message-text"]').setValue('We will, today')
    await wrapper.get('[role="dialog"] [data-testid="message-composer"]').trigger('submit')
    await flushPromises()

    expect(api.editCaseMessage).toHaveBeenCalledWith(REFERENCE, MINE, 'We will, today')
    expect(router.currentRoute.value.name).toBe('case')

    wrapper.unmount()
  })

  it('shows not-found for a message of the player, which the panel never edits', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: `/cases/p-0001/messages/${PLAYER_MSG}/edit`, conversation })
    const { wrapper } = await mount()

    expect(wrapper.find('[role="dialog"] [data-testid="message-text"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="routed-dialog-back"]').exists()).toBe(true)
    expect(api.editCaseMessage).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('redacts a message only as an admin, after a reason is chosen', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'], path: `/cases/p-0001/messages/${MINE}/redact`, conversation })
    const { wrapper, router } = await mount()

    expect(api.redactCaseMessage).not.toHaveBeenCalled()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'INAPPROPRIATE')
    await wrapper.get('[data-testid="redact-message-confirm"]').trigger('click')
    await flushPromises()

    expect(api.redactCaseMessage).toHaveBeenCalledWith(REFERENCE, MINE, 'INAPPROPRIATE')
    expect(router.currentRoute.value.name).toBe('case')

    wrapper.unmount()
  })
})

describe('case protocol address', () => {
  const version = (evidenceId: string, current: boolean): CaseProtocolVersion => ({
    appealId: 'a1',
    version: current ? 2 : 1,
    evidenceId,
    current,
    entryCount: 4,
    sourceDigest: 'abc',
    createdAt: Date.UTC(2026, 6, 2, 9, 0),
  })

  it('opens the protocol the address names and highlights its row', async () => {
    const { api, mount } = setup({
      roles: ['MODERATOR', 'VIEWER'],
      path: `/cases/p-0001/protocols/${EVIDENCE}`,
      protocols: [version(EVIDENCE, true), version(OLDER_EVIDENCE, false)],
    })
    const scroll = spyOnScroll()
    const { wrapper } = await mount()

    expect(api.openCaseProtocol).toHaveBeenCalledWith(REFERENCE, EVIDENCE)
    expect(wrapper.get('[data-testid="protocol-text"]').text()).toContain('Player: hello')
    expect(wrapper.get(`#event-${EVIDENCE}`).attributes('data-highlighted')).toBe('true')
    expect(scroll.scroll).toHaveBeenCalledTimes(1)

    scroll.restore()
    wrapper.unmount()
  })

  it('copies the address of a protocol version: the protocol route', async () => {
    const writes: string[] = []
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: '/cases/p-0001?tab=notes-audit', protocols: [version(EVIDENCE, true)] })
    const { wrapper } = await mount({ writeText: async (text) => void writes.push(text) })
    await flushPromises()

    await wrapper.get('[data-testid="protocol-version"] [data-testid="copy-link"]').trigger('click')
    await flushPromises()

    expect(writes).toEqual([`${window.location.origin}/cases/p-0001/protocols/${EVIDENCE}`])

    wrapper.unmount()
  })
})

describe('items addressed by a fragment', () => {
  it('scrolls to and highlights the note the address names, on the audit tab', async () => {
    const scroll = spyOnScroll()
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: `/cases/p-0001?tab=notes-audit#event-${NOTE}`, timeline: [noteEvent(5, 'looked at it')] })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="event-5"]').attributes('data-highlighted')).toBe('true')
    expect(scroll.scroll).toHaveBeenCalledWith({ block: 'center', behavior: 'smooth' })

    scroll.restore()
    wrapper.unmount()
  })

  it('scrolls without animation when the system asks for reduced motion', async () => {
    stubReducedMotion(true)
    const scroll = spyOnScroll()
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: `/cases/p-0001?tab=notes-audit#event-${NOTE}`, timeline: [noteEvent(5, 'x')] })
    const { wrapper } = await mount()

    expect(scroll.scroll).toHaveBeenCalledWith({ block: 'center', behavior: 'auto' })

    scroll.restore()
    wrapper.unmount()
  })

  it('pages on through the timeline until the note the address names is loaded', async () => {
    const scroll = spyOnScroll()
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: `/cases/p-0001?tab=notes-audit#event-${NOTE}` })
    api.caseTimeline.mockReset()
    api.caseTimeline
      .mockResolvedValueOnce(ok(timelinePage([timelineItem({ seq: 9, type: 'punishment.applied' })], 'cursor-2')))
      .mockResolvedValueOnce(ok(timelinePage([noteEvent(3, 'older note')])))
    const { wrapper } = await mount()

    expect(api.caseTimeline).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="event-3"]').attributes('data-highlighted')).toBe('true')

    scroll.restore()
    wrapper.unmount()
  })

  it('opens the top with no highlight when the address names an item that is not there', async () => {
    const scroll = spyOnScroll()
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: '/cases/p-0001?tab=notes-audit#event-3f2b8c1e-0000-4000-8000-0000000000ff' })
    const { wrapper } = await mount()

    expect(wrapper.findAll('[data-highlighted="true"]')).toHaveLength(0)
    expect(scroll.scroll).not.toHaveBeenCalled()

    scroll.restore()
    wrapper.unmount()
  })

  it('highlights a message the address names in the conversation', async () => {
    const scroll = spyOnScroll()
    const conversation = [playerMessage(1, PLAYER_MSG), staffMessage(2, MINE, 'We will')]
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: `/cases/p-0001?tab=conversation#event-${MINE}`, conversation })
    const { wrapper } = await mount()

    expect(wrapper.get(`[data-testid="message-${MINE}"]`).attributes('data-highlighted')).toBe('true')
    expect(wrapper.find(`[data-testid="message-${PLAYER_MSG}"]`).attributes('data-highlighted')).toBeUndefined()

    scroll.restore()
    wrapper.unmount()
  })

  it('copies the address of a note: the audit tab with the note as its fragment', async () => {
    const writes: string[] = []
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], path: '/cases/p-0001?tab=notes-audit', timeline: [noteEvent(5, 'x')] })
    const { wrapper } = await mount({ writeText: async (text) => void writes.push(text) })

    await wrapper.get('[data-testid="event-5"] [data-testid="copy-link"]').trigger('click')
    await flushPromises()

    expect(writes).toEqual([`${window.location.origin}/cases/p-0001?tab=notes-audit#event-${NOTE}`])

    wrapper.unmount()
  })

  it('never sends the fragment to the server', async () => {
    const requested: string[] = []
    const answer = async (input: Request | string | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://ui.test'), init)
      requested.push(request.url)
      const path = new URL(request.url).pathname
      if (path.endsWith('/timeline')) return new Response(JSON.stringify(timelinePage([noteEvent(5, 'x')])), { headers: { 'Content-Type': 'application/json' } })
      if (path.endsWith('/protocol')) return new Response(JSON.stringify({ items: [] }), { headers: { 'Content-Type': 'application/json' } })
      if (path.endsWith('/events')) return new Response(null, { status: 404 })
      if (path === '/api/cases') return new Response(JSON.stringify({ items: [], page: 0, size: 25, totalSize: 0, totalPages: 0 }), { headers: { 'Content-Type': 'application/json' } })
      if (path.endsWith('/evidence-shares')) return new Response(JSON.stringify({ items: [] }), { headers: { 'Content-Type': 'application/json' } })
      if (path === '/api/case/p-0001') return new Response(JSON.stringify(caseResponse()), { headers: { 'Content-Type': 'application/json' } })
      return new Response(JSON.stringify({ message: `no fake for ${path}` }), { status: 404, headers: { 'Content-Type': 'application/json' } })
    }
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: answer as unknown as typeof fetch })
    const scroll = spyOnScroll()
    const { wrapper } = await mountWithApp(Shell, { api, path: `/cases/p-0001?tab=notes-audit#event-${NOTE}`, roles: ['MODERATOR', 'VIEWER'] })
    await flushPromises()

    expect(requested.length).toBeGreaterThan(0)
    expect(requested.filter((url) => url.includes('#') || url.includes('event-'))).toEqual([])
    expect(wrapper.get('[data-testid="event-5"]').attributes('data-highlighted')).toBe('true')

    scroll.restore()
    wrapper.unmount()
  })
})
