import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { ApiResult, Case } from '../../api/types'
import { caseResponse, timelineItem, timelinePage } from '../../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { CASE_CONTEXT_KEY, type CaseStatusApplied } from './caseContext'
import CaseStatusDialog from './CaseStatusDialog.vue'

function setup(options: {
  action?: 'close' | 'reopen'
  roles?: string[]
  kase?: Partial<Case>
  updateCase?: (...args: unknown[]) => Promise<ApiResult<Case>>
  timeline?: ReturnType<typeof timelinePage>
} = {}) {
  const action = options.action ?? 'close'
  const updateCase = vi.fn(options.updateCase ?? (async () => ok(caseResponse({ version: 4, status: action === 'close' ? 'CLOSED' : 'WAITING' }))))
  const caseTimeline = vi.fn(async () => ok(options.timeline ?? timelinePage([])))
  const applied = vi.fn<(change: CaseStatusApplied) => void>()
  const context = { kase: ref<Case | null>(caseResponse(options.kase)), applied }
  return {
    updateCase,
    caseTimeline,
    applied,
    mount: () =>
      mountWithApp(CaseStatusDialog, {
        api: mockApi({ updateCase, caseTimeline }),
        path: `/cases/p-0001/${action}`,
        props: { reference: 'p-0001', action },
        roles: options.roles ?? ['MODERATOR', 'VIEWER'],
        provide: { [CASE_CONTEXT_KEY as symbol]: context },
      }),
  }
}

describe('CaseStatusDialog: opening the route', () => {
  it('sends nothing when the route is opened or reloaded', async () => {
    const { updateCase, mount } = setup({ action: 'close' })
    const { wrapper } = await mount()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(updateCase).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('asks for confirmation before it closes the case', async () => {
    const { updateCase, mount } = setup({ action: 'close' })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="case-status-confirm"]').text()).toBe('Close case')
    expect(updateCase).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('CaseStatusDialog: closing', () => {
  it('sends the close with the version that was read, then reports the previous status', async () => {
    const { updateCase, applied, mount } = setup({ action: 'close', kase: { status: 'IN_REVIEW', version: 3 } })
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="case-status-confirm"]').trigger('click')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 3, status: 'CLOSED' })
    expect(applied).toHaveBeenCalledWith(expect.objectContaining({ action: 'close', previous: 'IN_REVIEW' }))
    expect(applied.mock.calls[0]![0].kase).toMatchObject({ version: 4 })
    expect(router.currentRoute.value.name).toBe('case')

    wrapper.unmount()
  })

  it('sends the update to the case identifier, not to the reference in the address', async () => {
    const { updateCase, mount } = setup({ action: 'close', kase: { punishmentIdentifier: 'p-0042' } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="case-status-confirm"]').trigger('click')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0042', expect.anything())

    wrapper.unmount()
  })

  it('keeps the dialog open and says so on a conflict, and reports nothing as applied', async () => {
    const { applied, mount } = setup({ action: 'close', updateCase: async () => fail('stale', 409, 'CONFLICT') })
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="case-status-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="routed-dialog-failed"]').attributes('role')).toBe('alert')
    expect(wrapper.get('[data-testid="case-status-conflict"]').text()).toContain('Reload to see it.')
    expect(applied).not.toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('case-close')

    wrapper.unmount()
  })
})

describe('CaseStatusDialog: reopening', () => {
  it('reads the timeline and offers the status the case had before it was closed', async () => {
    const closed = timelineItem({ type: 'case.status_changed', payload: { from: 'WAITING', to: 'CLOSED' } })
    const { caseTimeline, mount } = setup({ action: 'reopen', kase: { status: 'CLOSED' }, timeline: timelinePage([closed]) })
    const { wrapper } = await mount()

    expect(caseTimeline).toHaveBeenCalledWith('p-0001', expect.objectContaining({ types: ['case'] }))
    expect(wrapper.get('[data-testid="case-status-body"]').text()).toContain('Waiting')

    wrapper.unmount()
  })

  it('sends the reopen to the status found in the timeline', async () => {
    const closed = timelineItem({ type: 'case.status_changed', payload: { from: 'WAITING', to: 'CLOSED' } })
    const { updateCase, mount } = setup({ action: 'reopen', kase: { status: 'CLOSED', version: 5 }, timeline: timelinePage([closed]) })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="case-status-confirm"]').trigger('click')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 5, status: 'WAITING' })

    wrapper.unmount()
  })

  it('says that the case reopens as Open when no earlier status is known', async () => {
    const { updateCase, mount } = setup({ action: 'reopen', kase: { status: 'CLOSED', version: 5 } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="case-status-body"]').text()).toContain('reopens as Open')
    await wrapper.get('[data-testid="case-status-confirm"]').trigger('click')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 5, status: 'OPEN' })

    wrapper.unmount()
  })
})

describe('CaseStatusDialog: roles', () => {
  it('gives a viewer no confirm action and sends nothing', async () => {
    const { updateCase, mount } = setup({ action: 'close', roles: ['VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="case-status-confirm"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="case-status-body"]').text()).toBe('A viewer cannot change a case.')
    expect(updateCase).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})
