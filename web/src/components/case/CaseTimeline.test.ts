import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CaseTimelineItem } from '../../api/types'
import { evidenceItem } from '../../test/evidence'
import { ME, NOTE, OTHER, timelineItem, timelinePage } from '../../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import CaseTimeline from './CaseTimeline.vue'

function setup(pages: CaseTimelineItem[][] = [[]], options: { roles?: string[]; extra?: Record<string, unknown> } = {}) {
  const queue = pages.map((items, index) => timelinePage(items, index + 1 < pages.length ? `cursor-${index + 1}` : null))
  const api = {
    caseTimeline: vi.fn(async (_reference: string, _query?: Record<string, unknown>) => ok(queue.shift() ?? timelinePage([]))),
    ...options.extra,
  }
  return {
    api,
    mount: () => mountWithApp(CaseTimeline, { api: mockApi(api), props: { reference: 'p-0001' }, roles: options.roles }),
  }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']
const titles = (wrapper: Wrapper) => wrapper.findAll('[data-testid="event-title"]').map((title) => title.text())

describe('CaseTimeline: event types', () => {
  it.each([
    ['punishment.applied', { section: 'network', stepIndex: 0, stepCount: 3, eloCost: 40, escalated: true }, 'Punishment applied', 'Step 1 of 3'],
    ['punishment.revoked', {}, 'Punishment revoked', ''],
    ['punishment.lifted', { approximate: true }, 'Punishment lifted by appeal', 'time is approximate'],
    ['punishment.expired', { expiredAt: Date.UTC(2026, 6, 2) }, 'Punishment expired', ''],
    ['evidence.attached', { evidenceId: 'e-1', kind: 'ANTICHEAT' }, 'Evidence attached', 'Anticheat record'],
    ['evidence.lifted', { evidenceId: 'e-1' }, 'Evidence lifted', ''],
    ['evidence.revoked', { evidenceId: 'e-1' }, 'Evidence revoked', ''],
    ['appeal.filed', { appealId: 'a-1', eligible: true }, 'Appeal filed', ''],
    ['appeal.decided', { appealId: 'a-1', decision: 'DENIED' }, 'Appeal decided', 'Deny the appeal'],
    ['case.status_changed', { from: 'CLOSED', to: 'OPEN' }, 'Status changed', 'Closed to Open'],
    ['case.assignee_changed', { from: null, to: OTHER }, 'Assignee changed', 'assigned to 00000000…'],
    ['case.tags_changed', { added: ['spam'], removed: ['chat'] }, 'Tags changed', 'added: spam · removed: chat'],
    ['case.priority_changed', { from: 'NORMAL', to: 'URGENT' }, 'Priority changed', 'Normal to Urgent'],
    ['note.added', { noteId: NOTE, text: 'hello' }, 'Note added', ''],
    ['note.edited', { noteId: NOTE, text: 'hello again' }, 'Note edited', ''],
    ['note.redacted', { noteId: NOTE, reason: 'PERSONAL_DATA' }, 'Note removed', 'Personal data'],
  ])('shows %s as a readable entry with its own icon', async (type, payload, title, detail) => {
    const { mount } = setup([[timelineItem({ type, payload })]])
    const { wrapper } = await mount()

    expect(titles(wrapper)).toEqual([title])
    expect(wrapper.get('[data-testid="event-icon"]').attributes('data-icon')).not.toBe('help')
    if (detail) expect(wrapper.get('[data-testid="event-detail"]').text()).toContain(detail)
    expect(wrapper.find('[data-testid="event-type"]').exists(), 'known types do not print their type name').toBe(false)

    wrapper.unmount()
  })

  it('shows an unknown type as a generic entry with its type name instead of dropping it', async () => {
    const { mount } = setup([[timelineItem({ type: 'message.added', payload: { text: 'from the portal' } })]])
    const { wrapper } = await mount()

    expect(titles(wrapper)).toEqual(['Other event'])
    expect(wrapper.get('[data-testid="event-type"]').text()).toBe('message.added')
    expect(wrapper.get('[data-testid="event-icon"]').attributes('data-icon')).toBe('help')

    wrapper.unmount()
  })

  it('lists the events as an ordered list of entries with machine readable times', async () => {
    const { mount } = setup([[timelineItem(), timelineItem({ type: 'appeal.filed' })]])
    const { wrapper } = await mount()

    expect(wrapper.findAll('ol > li[data-testid^="event-"]')).toHaveLength(2)
    expect(wrapper.findAll('li time[datetime]')).toHaveLength(2)
    expect(wrapper.get('[data-testid="timeline-status"]').attributes('aria-live')).toBe('polite')

    wrapper.unmount()
  })

  it('says so when no event matches', async () => {
    const { mount } = setup([[]])
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="timeline-empty"]').text()).toBe('No events match.')

    wrapper.unmount()
  })
})

describe('CaseTimeline: trust and origin', () => {
  it.each([
    ['VERIFIED', 'Verified'],
    ['UNVERIFIED', 'Unverified'],
    ['SYSTEM', 'System'],
  ] as const)('labels a %s actor with text, not colour alone', async (trust, label) => {
    const { mount } = setup([[timelineItem({ actor: { oid: OTHER, name: 'Ada', trust } })]])
    const { wrapper } = await mount()

    const badge = wrapper.get(`[data-testid="trust-${trust}"]`)
    expect(badge.text()).toBe(label)
    expect(badge.find('svg[aria-hidden="true"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-trust]')).toHaveLength(1)

    wrapper.unmount()
  })

  it('never gives an unverified actor the verified badge', async () => {
    const { mount } = setup([[timelineItem({ actor: { oid: null, name: null, trust: 'UNVERIFIED' } })]])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="trust-VERIFIED"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="actor-name"]').text()).toBe('Unknown')

    wrapper.unmount()
  })

  it('shows the actor name and falls back to the start of the oid when the name is gone', async () => {
    const { mount } = setup([[timelineItem({ actor: { oid: OTHER, name: 'Ada', trust: 'VERIFIED' } }), timelineItem({ actor: { oid: OTHER, name: null, trust: 'VERIFIED' } })]])
    const { wrapper } = await mount()

    expect(wrapper.findAll('[data-testid="actor-name"]').map((name) => name.text())).toEqual(['Ada', '00000000…'])

    wrapper.unmount()
  })

  it('shows no name for the system', async () => {
    const { mount } = setup([[timelineItem({ actor: { oid: null, name: null, trust: 'SYSTEM' } })]])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="actor-name"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('marks backfilled and reconstructed events and leaves live ones unmarked', async () => {
    const { mount } = setup([[timelineItem({ origin: 'BACKFILLED' }), timelineItem({ origin: 'RECONSTRUCTED' }), timelineItem({ origin: 'LIVE' })]])
    const { wrapper } = await mount()

    expect(wrapper.findAll('[data-testid="origin"]').map((marker) => marker.text())).toEqual(['Reconstructed from earlier data', 'Rebuilt afterwards'])

    wrapper.unmount()
  })
})

describe('CaseTimeline: paging and filters', () => {
  it('asks for the newest events first and offers more when the API has a cursor', async () => {
    const { api, mount } = setup([[timelineItem()], [timelineItem()]])
    const { wrapper } = await mount()

    expect(api.caseTimeline).toHaveBeenCalledWith('p-0001', expect.objectContaining({ order: 'desc', cursor: undefined }))
    expect(wrapper.find('[data-testid="load-more"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('loads the next page with the cursor, appends it and hides the button on the last page', async () => {
    const first = timelineItem({ type: 'appeal.filed' })
    const second = timelineItem({ type: 'punishment.expired', payload: { expiredAt: 1 } })
    const { api, mount } = setup([[first], [second]])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="load-more"]').trigger('click')
    await flushPromises()

    expect(api.caseTimeline).toHaveBeenLastCalledWith('p-0001', expect.objectContaining({ cursor: 'cursor-1' }))
    expect(titles(wrapper)).toEqual(['Appeal filed', 'Punishment expired'])
    expect(wrapper.find('[data-testid="load-more"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('keeps focus on the list instead of dropping it when the load more button goes away', async () => {
    const { mount } = setup([[timelineItem()], [timelineItem()]])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="load-more"]').trigger('click')
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('ol').element)

    wrapper.unmount()
  })

  it('sends the chosen type group and starts again from the first page', async () => {
    const { api, mount } = setup([[timelineItem(), timelineItem()], [timelineItem({ type: 'appeal.filed' })]])
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="filter-group"] [role="combobox"]'), 'appeal')
    await flushPromises()

    expect(api.caseTimeline).toHaveBeenLastCalledWith('p-0001', expect.objectContaining({ types: ['appeal'], cursor: undefined }))
    expect(titles(wrapper)).toEqual(['Appeal filed'])

    wrapper.unmount()
  })

  it('sends the actor trust filter', async () => {
    const { api, mount } = setup([[timelineItem()], [timelineItem()]])
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="filter-trust"] [role="combobox"]'), 'UNVERIFIED')
    await flushPromises()

    expect(api.caseTimeline).toHaveBeenLastCalledWith('p-0001', expect.objectContaining({ actorTrust: 'UNVERIFIED' }))

    wrapper.unmount()
  })

  it('sends the date range as the start and the end of the chosen days', async () => {
    const { api, mount } = setup([[timelineItem()], [timelineItem()]])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="filter-from"] input').setValue('2026-07-01')
    await flushPromises()
    await wrapper.get('[data-testid="filter-to"] input').setValue('2026-07-02')
    await flushPromises()

    const query = api.caseTimeline.mock.calls.at(-1)![1] as { from: number; to: number }
    expect(query.from).toBe(new Date('2026-07-01T00:00:00').getTime())
    expect(query.to).toBe(new Date('2026-07-02T23:59:59.999').getTime())

    wrapper.unmount()
  })

  it('shows a failed load with a retry', async () => {
    const api = { caseTimeline: vi.fn().mockResolvedValueOnce(fail('down', 500, 'UNKNOWN')).mockResolvedValueOnce(ok(timelinePage([timelineItem()]))) }
    const { wrapper } = await mountWithApp(CaseTimeline, { api: mockApi(api), props: { reference: 'p-0001' } })

    expect(wrapper.get('[role="alert"]').text()).toContain('down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(titles(wrapper)).toEqual(['Punishment applied'])

    wrapper.unmount()
  })
})

describe('CaseTimeline: notes', () => {
  const added = () => timelineItem({ seq: 1, type: 'note.added', payload: { noteId: NOTE, text: 'first text' }, subjectId: NOTE, actor: { oid: ME, name: 'Me', trust: 'VERIFIED' } })
  const edited = () => timelineItem({ seq: 2, type: 'note.edited', payload: { noteId: NOTE, text: 'second text' }, subjectId: NOTE, actor: { oid: ME, name: 'Me', trust: 'VERIFIED' } })

  it('shows the latest text of an edited note with an edited marker and the earlier version one click away', async () => {
    const { mount } = setup([[edited(), added()]])
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="note-body"]').text()).toBe('second text')
    expect(wrapper.get('[data-testid="edited"]').text()).toBe('edited')
    expect(wrapper.get('[data-testid="note-history"] summary').text()).toBe('Earlier versions (1)')
    expect(wrapper.get('[data-testid="note-history"]').text()).toContain('first text')
    expect(wrapper.find('[data-testid="note-superseded"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows a redacted note as removed without any text', async () => {
    const redacted = timelineItem({ seq: 1, type: 'note.added', payload: {}, redactedAt: Date.UTC(2026, 6, 3), subjectId: NOTE })
    const { mount } = setup([[redacted]], { roles: ['ADMIN', 'MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="note-removed"]').text()).toBe('Removed (data protection)')
    expect(wrapper.find('[data-testid="note-body"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="note-edit"]').exists(), 'nothing to edit').toBe(false)
    expect(wrapper.find('[data-testid="note-redact"]').exists(), 'nothing to redact twice').toBe(false)

    wrapper.unmount()
  })

  it('offers a viewer neither edit nor redact', async () => {
    const { mount } = setup([[added()]], { roles: ['VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="note-edit"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="note-redact"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('lets a moderator edit their own note but not redact it', async () => {
    const { mount } = setup([[added()]], { roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="note-edit"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="note-redact"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it("does not offer a moderator to edit somebody else's note", async () => {
    const theirs = timelineItem({ type: 'note.added', payload: { noteId: NOTE, text: 'theirs' }, subjectId: NOTE, actor: { oid: OTHER, name: 'Ada', trust: 'VERIFIED' } })
    const { mount } = setup([[theirs]], { roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="note-edit"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('does not treat an unverified author as the signed-in user', async () => {
    const legacy = timelineItem({ type: 'note.added', payload: { noteId: NOTE, text: 'old' }, subjectId: NOTE, actor: { oid: ME, name: null, trust: 'UNVERIFIED' } })
    const { mount } = setup([[legacy]], { roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="note-edit"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('lets an admin edit and redact any note, on its latest version only', async () => {
    const theirs = timelineItem({ seq: 1, type: 'note.added', payload: { noteId: NOTE, text: 'theirs' }, subjectId: NOTE, actor: { oid: OTHER, name: 'Ada', trust: 'VERIFIED' } })
    const later = timelineItem({ seq: 2, type: 'note.edited', payload: { noteId: NOTE, text: 'theirs, fixed' }, subjectId: NOTE, actor: { oid: OTHER, name: 'Ada', trust: 'VERIFIED' } })
    const { mount } = setup([[later, theirs]])
    const { wrapper } = await mount()

    expect(wrapper.findAll('[data-testid="note-edit"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="note-redact"]')).toHaveLength(1)

    wrapper.unmount()
  })

  it('saves an edit, tells the page and reloads the timeline', async () => {
    const editCaseNote = vi.fn(async () => ok({ noteId: NOTE, seq: 3 }))
    const { api, mount } = setup([[added()], [edited(), added()]], { roles: ['MODERATOR', 'VIEWER'], extra: { editCaseNote } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="note-edit"]').trigger('click')
    await wrapper.get('[data-testid="note-text"]').setValue('second text')
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    await flushPromises()

    expect(editCaseNote).toHaveBeenCalledWith('p-0001', NOTE, 'second text')
    expect(wrapper.emitted('changed')).toEqual([['Note saved.']])
    expect(api.caseTimeline).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="note-body"]').text()).toBe('second text')

    wrapper.unmount()
  })

  it('keeps the editor open with the server message when an edit is rejected', async () => {
    const editCaseNote = vi.fn(async () => fail('a note must not contain a UUID or an IP address', 400))
    const { mount } = setup([[added()]], { roles: ['MODERATOR', 'VIEWER'], extra: { editCaseNote } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="note-edit"]').trigger('click')
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="note-error"]').text()).toBe('a note must not contain a UUID or an IP address')
    expect(wrapper.find('[data-testid="note-editor"]').exists()).toBe(true)

    wrapper.unmount()
  })
})

describe('CaseTimeline: redaction', () => {
  const note = () => timelineItem({ seq: 1, type: 'note.added', payload: { noteId: NOTE, text: 'secret' }, subjectId: NOTE })

  it('asks for a reason from the backend list before it removes anything', async () => {
    const redactCaseNote = vi.fn(async () => ok({ noteId: NOTE, redactedVersions: 1 }))
    const { mount } = setup([[note()], [timelineItem({ seq: 1, type: 'note.added', payload: {}, redactedAt: 1, subjectId: NOTE })]], { extra: { redactCaseNote } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="note-redact"]').trigger('click')
    await flushPromises()

    expect(redactCaseNote).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="redact-confirm"]').attributes('disabled')).toBeDefined()

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'ERASURE_REQUEST')
    await wrapper.get('[data-testid="redact-confirm"]').trigger('click')
    await flushPromises()

    expect(redactCaseNote).toHaveBeenCalledWith('p-0001', NOTE, 'ERASURE_REQUEST')
    expect(wrapper.emitted('changed')).toEqual([['Note text removed.']])
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="note-removed"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows the error and keeps the dialog open when the backend refuses', async () => {
    const redactCaseNote = vi.fn(async () => fail('not allowed', 403))
    const { mount } = setup([[note()]], { extra: { redactCaseNote } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="note-redact"]').trigger('click')
    await flushPromises()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'OTHER')
    await wrapper.get('[data-testid="redact-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="redact-error"]').text()).toBe('not allowed')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('does not remove anything when the dialog is cancelled', async () => {
    const redactCaseNote = vi.fn()
    const { mount } = setup([[note()]], { extra: { redactCaseNote } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="note-redact"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="redact-cancel"]').trigger('click')
    await flushPromises()

    expect(redactCaseNote).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('CaseTimeline: inline evidence', () => {
  const attached = () => timelineItem({ type: 'evidence.attached', payload: { evidenceId: 'e-1', kind: 'LINK' } })

  it('reads no evidence until it is asked for', async () => {
    const evidenceItemCall = vi.fn()
    const { mount } = setup([[attached()]], { extra: { evidenceItem: evidenceItemCall } })
    const { wrapper } = await mount()

    expect(evidenceItemCall).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="evidence-toggle"]').attributes('aria-expanded')).toBe('false')

    wrapper.unmount()
  })

  it('shows the item through the existing evidence component when asked', async () => {
    const evidenceItemCall = vi.fn(async () => ok(evidenceItem({ identifier: 'e-1', kind: 'LINK', reference: 'https://example.com/replay' })))
    const { mount } = setup([[attached()]], { extra: { evidenceItem: evidenceItemCall } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="evidence-toggle"]').trigger('click')
    await flushPromises()

    expect(evidenceItemCall).toHaveBeenCalledWith('e-1')
    expect(wrapper.get('[data-testid="evidence-item"]').text()).toContain('example.com')
    expect(wrapper.get('[data-testid="evidence-toggle"]').attributes('aria-expanded')).toBe('true')

    wrapper.unmount()
  })

  it('says so when the evidence was deleted meanwhile', async () => {
    const evidenceItemCall = vi.fn(async () => fail('gone', 404, 'NOT_FOUND'))
    const { mount } = setup([[attached()]], { extra: { evidenceItem: evidenceItemCall } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="evidence-toggle"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="evidence-error"]').text()).toBe('This evidence is no longer available.')

    wrapper.unmount()
  })
})
