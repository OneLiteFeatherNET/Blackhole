import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { RouterView } from 'vue-router'
import type { Evidence } from '../api/types'
import { caseResponse, timelinePage } from '../test/cases'
import { evidenceItem } from '../test/evidence'
import { chooseOption } from '../test/select'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'

/** The app shell: the router view that renders the item page and, behind it, its delete route. */
const Shell = defineComponent({ setup: () => () => h(RouterView) })

const EVIDENCE = '3f2b8c1e-0000-4000-8000-0000000000e2'
const CASE = 'p-0001'

function setup(options: { roles?: string[]; path?: string; item?: Evidence | null; extra?: Record<string, unknown> } = {}) {
  const item = options.item === undefined ? evidenceItem({ identifier: EVIDENCE, punishmentIdentifier: CASE, kind: 'CHAT_LOG' }) : options.item
  const api = {
    evidenceItem: vi.fn(async () => (item === null ? fail('not found', 404, 'NOT_FOUND') : ok(item))),
    deleteEvidence: vi.fn(async () => ok(1)),
    evidenceContent: vi.fn(async () => ok(new Blob(['x']))),
    // After a delete the page of the case is shown: its reads answer like an empty case.
    cases: vi.fn(async () => ok({ items: [], page: 0, size: 25, totalSize: 0, totalPages: 0 })),
    caseDetail: vi.fn(async () => ok(caseResponse())),
    caseTimeline: vi.fn(async () => ok(timelinePage([]))),
    caseConversation: vi.fn(async () => ok([])),
    caseProtocols: vi.fn(async () => ok([])),
    caseDecidedAppeals: vi.fn(async () => ok([])),
    openCaseEvents: () => ({ close() {} }),
    ...options.extra,
  }
  return {
    api,
    mount: () => mountWithApp(Shell, { api: mockApi(api), path: options.path ?? `/evidence/${EVIDENCE}`, roles: options.roles }),
  }
}

describe('EvidenceItemView: the item', () => {
  it('loads the item by its id and shows it with a link to its case', async () => {
    const { api, mount } = setup({ roles: ['VIEWER'] })
    const { wrapper } = await mount()

    expect(api.evidenceItem).toHaveBeenCalledWith(EVIDENCE)
    expect(wrapper.get('[data-testid="evidence-case-link"]').attributes('href')).toBe(`/cases/${CASE}`)
    expect(wrapper.find('[data-testid="evidence-item"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows no delete button to a moderator, and nothing is sent by opening the page', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="delete"]').exists()).toBe(false)
    expect(api.deleteEvidence).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('shows the error and no item for an id that does not exist', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'], item: null })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="evidence-item"]').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(api.deleteEvidence).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('has no case link for an item that is not attached to a punishment', async () => {
    const { mount } = setup({ roles: ['VIEWER'], item: evidenceItem({ identifier: EVIDENCE, punishmentIdentifier: null }) })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="evidence-case-link"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('EvidenceItemView: deleting', () => {
  it('opens the delete dialog from the address and sends nothing until a reason is confirmed', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'], path: `/evidence/${EVIDENCE}/delete` })
    const { wrapper } = await mount()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(api.deleteEvidence).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('deletes the item with its reason once confirmed, then shows its case', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'], path: `/evidence/${EVIDENCE}/delete` })
    const { wrapper, router } = await mount()

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'WRONG_EVIDENCE')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.deleteEvidence).toHaveBeenCalledWith(EVIDENCE, { reason: 'WRONG_EVIDENCE', note: '' })
    expect(router.currentRoute.value.name).toBe('case')
    expect(router.currentRoute.value.params.reference).toBe(CASE)

    wrapper.unmount()
  })

  it('closes on Cancel without deleting anything', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'], path: `/evidence/${EVIDENCE}/delete` })
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="cancel"]').trigger('click')
    await flushPromises()

    expect(api.deleteEvidence).not.toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('evidence-item')

    wrapper.unmount()
  })
})
