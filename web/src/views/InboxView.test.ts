import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { DashboardSummary, InboxSummary } from '../api/types'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import InboxView from './InboxView.vue'

const OWNER = 'ab'.repeat(64)
const OTHER = 'cd'.repeat(64)

function item(id: string, since: number, reference: string | null = 'ABCD0001', owner: string | null = OWNER) {
  return { id, reference, owner, since }
}

function inbox(overrides: Partial<InboxSummary> = {}): InboxSummary {
  return {
    generatedAt: Date.UTC(2030, 0, 3, 12, 0),
    needsAnswer: { count: 1, items: [item('appeal-1', Date.UTC(2030, 0, 1, 9, 0))] },
    openAppeals: {
      count: 4,
      items: [item('appeal-1', Date.UTC(2030, 0, 1, 9, 0)), item('appeal-2', Date.UTC(2030, 0, 2, 9, 0), 'ABCD0002')],
    },
    newReports: { count: 0, items: [] },
    unassignedCases: { count: 12, items: [item('p-9', Date.UTC(2030, 0, 2, 9, 0), 'ABCD0009', OTHER)] },
    ...overrides,
  }
}

function dashboard(): DashboardSummary {
  return {
    activePunishmentsBySection: [{ section: 'network', active: 12 }],
    openReports: 0,
    pendingAppeals: 4,
    escalationsLast7Days: 0,
    latestEscalations: [],
  }
}

function mounting(overrides: { inbox?: () => Promise<unknown>; dashboard?: () => Promise<unknown> } = {}) {
  return mountWithApp(InboxView, {
    api: mockApi({
      inbox: overrides.inbox ?? (async () => ok(inbox())),
      dashboard: overrides.dashboard ?? (async () => ok(dashboard())),
    }),
  })
}

describe('InboxView', () => {
  it('shows the four sections with their counts', async () => {
    const { wrapper } = await mounting()

    expect(wrapper.get('[data-testid="section-needsAnswer"]').text()).toContain('Needs an answer')
    expect(wrapper.get('[data-testid="section-needsAnswer"]').text()).toContain('1')
    expect(wrapper.get('[data-testid="section-openAppeals"]').text()).toContain('Open appeals')
    expect(wrapper.get('[data-testid="section-openAppeals"]').text()).toContain('4')
    expect(wrapper.get('[data-testid="section-newReports"]').text()).toContain('New reports')
    expect(wrapper.get('[data-testid="section-unassignedCases"]').text()).toContain('Unassigned cases')
    expect(wrapper.get('[data-testid="section-unassignedCases"]').text()).toContain('12')

    wrapper.unmount()
  })

  it('lists the items of a section in the order the API gives them, each linked to its case', async () => {
    const { wrapper } = await mounting()

    const links = wrapper.get('[data-testid="section-openAppeals"]').findAll('a[href^="/cases/"]')
    expect(links.map((link) => link.attributes('href'))).toEqual(['/cases/ABCD0001', '/cases/ABCD0002'])

    wrapper.unmount()
  })

  it('links each section to its full list', async () => {
    const { wrapper } = await mounting()

    const showAll = (key: string) => wrapper.get(`[data-testid="section-${key}"]`).get('[data-testid="show-all"]').attributes('href')
    expect(showAll('needsAnswer')).toBe('/appeals')
    expect(showAll('openAppeals')).toBe('/appeals')
    expect(showAll('newReports')).toBe('/reports')
    expect(showAll('unassignedCases')).toBe('/cases')

    wrapper.unmount()
  })

  it('says that a section is empty instead of hiding it', async () => {
    const { wrapper } = await mounting()

    expect(wrapper.get('[data-testid="section-newReports"]').text()).toContain('Nothing waiting')

    wrapper.unmount()
  })

  it('keeps the active punishments and the escalations below the sections', async () => {
    const { wrapper } = await mounting()

    expect(wrapper.get('[data-testid="section-network"]').text()).toContain('12')

    wrapper.unmount()
  })

  it('has exactly one level-one heading, the inbox title', async () => {
    const { wrapper } = await mounting()

    const headings = wrapper.findAll('h1')
    expect(headings).toHaveLength(1)
    expect(headings[0]!.text()).toBe('Inbox')

    wrapper.unmount()
  })

  it('shows the error of a failed read with a retry that reads the inbox again', async () => {
    const inboxRead = vi
      .fn()
      .mockResolvedValueOnce(fail('backend down', 503, 'UNKNOWN'))
      .mockResolvedValueOnce(ok(inbox()))
    const { wrapper } = await mounting({ inbox: inboxRead })

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="section-openAppeals"]').text()).toContain('4')
    expect(inboxRead).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })
})
