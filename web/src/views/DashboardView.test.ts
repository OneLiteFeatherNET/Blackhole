import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { DashboardSummary } from '../api/types'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import DashboardView from './DashboardView.vue'

const OWNER = 'ab'.repeat(64)

function summary(overrides: Partial<DashboardSummary> = {}): DashboardSummary {
  return {
    activePunishmentsBySection: [
      { section: 'chat', active: 3 },
      { section: 'network', active: 12 },
    ],
    openReports: 5,
    pendingAppeals: 2,
    escalationsLast7Days: 7,
    latestEscalations: [
      { identifier: 'p-1', owner: OWNER, section: 'network', createdAt: Date.UTC(2030, 0, 2, 12, 0), lifted: false },
      { identifier: 'p-2', owner: 'cd'.repeat(64), section: 'chat', createdAt: Date.UTC(2030, 0, 1, 12, 0), lifted: true },
    ],
    ...overrides,
  }
}

describe('DashboardView', () => {
  it('shows the counters, the sections and the latest escalations', async () => {
    const dashboard = vi.fn(async () => ok(summary()))
    const { wrapper } = await mountWithApp(DashboardView, { api: mockApi({ dashboard }) })

    const text = wrapper.text()
    expect(text, 'open reports').toContain('Open reports')
    expect(wrapper.get('[data-testid="open-reports"]').text()).toBe('5')
    expect(wrapper.get('[data-testid="pending-appeals"]').text()).toBe('2')
    expect(wrapper.get('[data-testid="escalations-7d"]').text()).toBe('7')
    expect(wrapper.get('[data-testid="section-network"]').text()).toContain('12')
    expect(wrapper.get('[data-testid="section-chat"]').text()).toContain('3')
    expect(wrapper.findAll('tbody tr'), 'one row per escalation').toHaveLength(2)
    expect(dashboard).toHaveBeenCalledOnce()

    wrapper.unmount()
  })

  it('links an escalation to the player detail by owner hash and marks lifted ones', async () => {
    const { wrapper } = await mountWithApp(DashboardView, { api: mockApi({ dashboard: async () => ok(summary()) }) })

    const link = wrapper.get(`a[href="/players/${OWNER}"]`)
    expect(link.text(), 'only a shortened hash is shown').toBe(`${OWNER.slice(0, 8)}…`)
    expect(wrapper.findAll('tbody tr')[1]!.text(), 'lifted escalation').toContain('Lifted')

    wrapper.unmount()
  })

  it('shows an empty message when nothing has escalated', async () => {
    const { wrapper } = await mountWithApp(DashboardView, {
      api: mockApi({ dashboard: async () => ok(summary({ latestEscalations: [] })) }),
    })

    expect(wrapper.text()).toContain('No automatic permanent punishments yet.')
    expect(wrapper.find('table').exists(), 'no table without rows').toBe(false)

    wrapper.unmount()
  })

  it('shows the error and reloads on retry', async () => {
    const dashboard = vi
      .fn()
      .mockResolvedValueOnce(fail('backend down', 0, 'NETWORK'))
      .mockResolvedValueOnce(ok(summary()))
    const { wrapper } = await mountWithApp(DashboardView, { api: mockApi({ dashboard }) })

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="open-reports"]').text()).toBe('5')
    expect(dashboard).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('shows a progress bar while loading', async () => {
    let resolve: (value: Awaited<ReturnType<typeof ok<DashboardSummary>>>) => void = () => {}
    const pending = new Promise<ReturnType<typeof ok<DashboardSummary>>>((r) => (resolve = r))
    const { wrapper } = await mountWithApp(DashboardView, { api: mockApi({ dashboard: () => pending }) })

    expect(wrapper.find('[role="progressbar"]').exists(), 'progress while pending').toBe(true)
    resolve(ok(summary()))
    await flushPromises()
    expect(wrapper.find('[role="progressbar"]').exists(), 'progress gone when loaded').toBe(false)

    wrapper.unmount()
  })
})
