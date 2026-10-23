import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { caseResponse, NOTE, timelineItem, timelinePage } from '../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import { chooseOption } from '../test/select'
import CaseView from './CaseView.vue'

function setup(options: { roles?: string[]; kase?: Parameters<typeof caseResponse>[0]; extra?: Record<string, unknown> } = {}) {
  const api = {
    caseDetail: vi.fn(async () => ok(caseResponse(options.kase))),
    caseTimeline: vi.fn(async () => ok(timelinePage([timelineItem()]))),
    updateCase: vi.fn(async () => ok(caseResponse({ ...options.kase, version: 4, status: 'WAITING' }))),
    addCaseNote: vi.fn(async () => ok({ noteId: NOTE, seq: 9 })),
    ...options.extra,
  }
  return {
    api,
    mount: () => mountWithApp(CaseView, { api: mockApi(api), path: '/cases/p-0001', props: { reference: 'p-0001' }, roles: options.roles }),
  }
}

describe('CaseView: loading', () => {
  it('loads the case and its timeline by the reference in the route', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(api.caseDetail).toHaveBeenCalledWith('p-0001')
    expect(api.caseTimeline).toHaveBeenCalledWith('p-0001', expect.anything())
    expect(wrapper.get('h2').text()).toBe('Case #AB12CD')
    expect(wrapper.findAll('[data-testid^="event-"][data-type]')).toHaveLength(1)

    wrapper.unmount()
  })

  it('shows a case that does not exist as an error with a retry', async () => {
    const { wrapper } = await mountWithApp(CaseView, {
      api: mockApi({ caseDetail: vi.fn(async () => fail('Unknown punishment', 404, 'NOT_FOUND')) }),
      props: { reference: 'nope' },
    })

    expect(wrapper.get('[role="alert"]').text()).toContain('Unknown punishment')
    expect(wrapper.find('[data-testid="case-header"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('links the player’s other cases', async () => {
    const related = [
      { identifier: 'p-0002', shortId: '#ZZ99XX', section: 'chat', punishmentState: 'EXPIRED', caseStatus: 'CLOSED', createdAt: Date.UTC(2026, 5, 1) },
      { identifier: 'p-0003', shortId: '#QQ11WW', section: null, punishmentState: 'ACTIVE', caseStatus: 'OPEN', createdAt: Date.UTC(2026, 5, 2) },
    ]
    const { mount } = setup({ kase: { related } })
    const { wrapper, router } = await mount()

    const links = wrapper.findAll('[data-testid="related"] a')
    expect(links.map((link) => link.text())).toEqual(['#ZZ99XX', '#QQ11WW'])
    expect(router.resolve({ name: 'case', params: { reference: 'p-0002' } }).href).toBe(links[0]!.attributes('href'))

    wrapper.unmount()
  })

  it('says when the player has no other cases', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="related"]').text()).toContain('no other cases')

    wrapper.unmount()
  })
})

describe('CaseView: roles', () => {
  it('gives a viewer a read-only page without a notes editor', async () => {
    const { mount } = setup({ roles: ['VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="note-editor"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="notes-read-only"]').text()).toContain('moderator role')
    expect(wrapper.get('[data-testid="status"] [role="combobox"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('gives a moderator the editor and the metadata controls', async () => {
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="note-editor"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="status"] [role="combobox"]').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })
})

describe('CaseView: changing the case', () => {
  it('updates the header and reloads the timeline after a change', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="status"] [role="combobox"]'), 'WAITING')
    await flushPromises()

    expect(api.updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 3, status: 'WAITING' })
    expect(wrapper.get('[data-testid="status"] [role="combobox"]').text()).toContain('Waiting')
    expect(api.caseTimeline).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[role="status"]').map((live) => live.text())).toContain('Case saved.')

    wrapper.unmount()
  })

  it('on a conflict keeps the old values, then reloads both case and timeline when asked', async () => {
    const detail = vi.fn()
    detail.mockResolvedValueOnce(ok(caseResponse())).mockResolvedValueOnce(ok(caseResponse({ version: 7, status: 'CLOSED' })))
    const { api, mount } = setup({ extra: { caseDetail: detail, updateCase: vi.fn(async () => fail('stale', 409, 'CONFLICT')) } })
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="priority"] [role="combobox"]'), 'HIGH')
    await flushPromises()
    expect(wrapper.find('[data-testid="conflict"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="status"] [role="combobox"]').text()).toContain('Open')

    await wrapper.get('[data-testid="reload"]').trigger('click')
    await flushPromises()

    expect(detail).toHaveBeenCalledTimes(2)
    expect(api.caseTimeline).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="conflict"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="status"] [role="combobox"]').text()).toContain('Closed')

    wrapper.unmount()
  })
})

describe('CaseView: notes', () => {
  it('adds a note, clears the editor, reloads the timeline and announces the save', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="note-text"]').setValue('looked at the logs')
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    await flushPromises()

    expect(api.addCaseNote).toHaveBeenCalledWith('p-0001', 'looked at the logs')
    expect((wrapper.get('[data-testid="note-text"]').element as HTMLTextAreaElement).value).toBe('')
    expect(api.caseTimeline).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[role="status"][aria-live="polite"]').map((live) => live.text())).toContain('Note saved.')

    wrapper.unmount()
  })

  it('restores the text with the server message when the note is rejected', async () => {
    const addCaseNote = vi.fn(async () => fail('a note must not contain a UUID or an IP address; name a player by the short id', 400))
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'], extra: { addCaseNote } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="note-text"]').setValue('player 8.8.8.8')
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="note-error"]').text()).toContain('UUID or an IP address')
    expect((wrapper.get('[data-testid="note-text"]').element as HTMLTextAreaElement).value).toBe('player 8.8.8.8')
    expect(api.caseTimeline, 'the timeline is not reloaded for a failed note').toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })
})

describe('CaseView: redaction as admin', () => {
  it('removes a note after confirming with a reason and shows it as removed', async () => {
    const note = timelineItem({ seq: 1, type: 'note.added', payload: { noteId: NOTE, text: 'phone 555' }, subjectId: NOTE })
    const removed = timelineItem({ seq: 1, type: 'note.added', payload: {}, redactedAt: 1, subjectId: NOTE })
    const caseTimeline = vi.fn().mockResolvedValueOnce(ok(timelinePage([note]))).mockResolvedValueOnce(ok(timelinePage([removed])))
    const redactCaseNote = vi.fn(async () => ok({ noteId: NOTE, redactedVersions: 1 }))
    const { mount } = setup({ extra: { caseTimeline, redactCaseNote } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="note-redact"]').trigger('click')
    await flushPromises()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'PERSONAL_DATA')
    await wrapper.get('[data-testid="redact-confirm"]').trigger('click')
    await flushPromises()

    expect(redactCaseNote).toHaveBeenCalledWith('p-0001', NOTE, 'PERSONAL_DATA')
    expect(wrapper.get('[data-testid="note-removed"]').text()).toBe('Removed (data protection)')
    expect(wrapper.text()).not.toContain('phone 555')

    wrapper.unmount()
  })
})
