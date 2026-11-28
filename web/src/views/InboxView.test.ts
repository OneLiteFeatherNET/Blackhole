import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BlackholeApi } from '../api/blackhole'
import type { DashboardSummary, InboxChangedItem, InboxChangedSection, InboxItem, InboxSummary } from '../api/types'
import { caseResponse } from '../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import { SIGNED_IN_OID } from '../test/mount'
import { caseListItem, fakeCaseList } from '../test/inbox'
import InboxView from './InboxView.vue'

const ME = SIGNED_IN_OID
const OTHER = '00000000-0000-4000-8000-0000000000b2'
const OWNER = 'ab'.repeat(64)
const NOW = Date.UTC(2030, 0, 3, 12, 0)
const HOUR = 3_600_000
const DAY = 86_400_000

beforeEach(() => {
  // Only the clock and the timers are faked: the page reads the time for ages and the undo window waits on a timer.
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

function appealItem(id: string, since: number, reference = 'APP00001'): InboxItem {
  return { id, reference, owner: OWNER, since }
}

function inbox(overrides: Partial<InboxSummary> = {}): InboxSummary {
  return {
    generatedAt: NOW,
    needsAnswer: { count: 1, items: [appealItem('answer-1', NOW - 2 * HOUR, 'ANS00001')] },
    openAppeals: {
      count: 4,
      items: [appealItem('appeal-1', NOW - 5 * HOUR, 'APP00001'), appealItem('appeal-2', NOW - DAY, 'APP00002')],
    },
    newReports: { count: 1, items: [{ id: 'report-1', owner: OWNER, since: NOW - 3 * HOUR }] },
    unassignedCases: { count: 0, items: [] },
    ...overrides,
  }
}

function changedItem(overrides: Partial<InboxChangedItem> = {}): InboxChangedItem {
  return { kind: 'CASE', id: 'AB12CD34', change: 'STATUS', byName: 'Anna', at: NOW - 2 * HOUR, ...overrides }
}

function changedSection(items: InboxChangedItem[] = [], count = items.length): InboxChangedSection {
  return { count, items }
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

const MY_OPEN_CASES = [
  caseListItem({ shortId: 'MINE0001', punishmentIdentifier: 'p-mine-1', assigneeOid: ME, status: 'OPEN', lastActivityAt: NOW - 4 * HOUR }),
  caseListItem({ shortId: 'MINE0002', punishmentIdentifier: 'p-mine-2', assigneeOid: ME, status: 'IN_REVIEW', lastActivityAt: NOW - 9 * HOUR }),
]

function mounting(
  options: {
    cases?: ReturnType<typeof fakeCaseList>['read']
    updateCase?: BlackholeApi['updateCase']
    inbox?: () => Promise<unknown>
    changedByOthers?: () => Promise<unknown>
    roles?: string[]
    oid?: string | null
  } = {},
) {
  return mountWithApp(InboxView, {
    roles: options.roles,
    oid: options.oid,
    api: mockApi({
      inbox: options.inbox ?? (async () => ok(inbox())),
      changedByOthers: options.changedByOthers ?? (async () => ok(changedSection())),
      dashboard: async () => ok(dashboard()),
      cases: options.cases ?? fakeCaseList(MY_OPEN_CASES).read,
      updateCase: options.updateCase,
      joinGuard: async () => ok({ manualLevel: 'OFF', automaticLevel: 'OFF', effectiveLevel: 'OFF', updatedAt: 0 }),
      areaBans: async () => ok([]),
    }),
  })
}

function rowHrefs(wrapper: Awaited<ReturnType<typeof mounting>>['wrapper'], queue: string): string[] {
  return wrapper.get(`[data-queue="${queue}"]`).findAll('[data-testid="row"] a').map((link) => link.attributes('href') ?? '')
}

describe('InboxView', () => {
  it('shows the six queue sections in the order of the work queue', async () => {
    const { wrapper } = await mounting()

    expect(wrapper.findAll('[data-queue]').map((section) => section.attributes('data-queue'))).toEqual([
      'assigned',
      'urgent',
      'appeals',
      'answers',
      'reports',
      'changed',
    ])

    wrapper.unmount()
  })

  it('counts the open cases the caller holds and leaves the closed ones out', async () => {
    const cases = fakeCaseList([
      ...MY_OPEN_CASES,
      caseListItem({ shortId: 'MINE0003', assigneeOid: ME, status: 'CLOSED' }),
      caseListItem({ shortId: 'THEIRS', assigneeOid: OTHER, status: 'OPEN' }),
    ])
    const { wrapper } = await mounting({ cases: cases.read })

    expect(wrapper.get('[data-queue="assigned"] [data-testid="section-count"]').text()).toBe('2')

    wrapper.unmount()
  })

  it('shows at most ten rows of a section, and its full count', async () => {
    const many = Array.from({ length: 12 }, (_, index) =>
      caseListItem({ shortId: `MANY${index}`, punishmentIdentifier: `p-many-${index}`, assigneeOid: ME, lastActivityAt: NOW - index * HOUR }),
    )
    const { wrapper } = await mounting({ cases: fakeCaseList(many).read })

    expect(wrapper.get('[data-queue="assigned"] [data-testid="section-count"]').text()).toBe('12')
    expect(wrapper.get('[data-queue="assigned"]').findAll('[data-testid="row"]')).toHaveLength(10)

    wrapper.unmount()
  })

  it('lists the assigned cases oldest activity first', async () => {
    const { wrapper } = await mounting()

    expect(rowHrefs(wrapper, 'assigned')).toEqual(['/cases/p-mine-2', '/cases/p-mine-1'])

    wrapper.unmount()
  })

  it('lists unassigned cases that are URGENT or HIGH and leaves the others out', async () => {
    const cases = fakeCaseList([
      caseListItem({ shortId: 'URG1', punishmentIdentifier: 'p-urg', priority: 'URGENT' }),
      caseListItem({ shortId: 'HIG1', punishmentIdentifier: 'p-high', priority: 'HIGH' }),
      caseListItem({ shortId: 'NRM1', punishmentIdentifier: 'p-normal', priority: 'NORMAL' }),
      caseListItem({ shortId: 'URGA', punishmentIdentifier: 'p-urg-assigned', priority: 'URGENT', assigneeOid: OTHER }),
    ])
    const { wrapper } = await mounting({ cases: cases.read })

    expect(rowHrefs(wrapper, 'urgent')).toEqual(['/cases/p-urg', '/cases/p-high'])
    expect(wrapper.get('[data-queue="urgent"] [data-testid="section-count"]').text()).toBe('2')

    wrapper.unmount()
  })

  it('keeps the appeal, answer and report sections on the inbox read, with their counts and rows', async () => {
    const { wrapper } = await mounting()

    expect(wrapper.get('[data-queue="appeals"] [data-testid="section-count"]').text()).toBe('4')
    expect(rowHrefs(wrapper, 'appeals')).toEqual(['/appeals/appeal-1', '/appeals/appeal-2'])
    expect(wrapper.get('[data-queue="answers"] [data-testid="section-count"]').text()).toBe('1')
    expect(rowHrefs(wrapper, 'reports')).toEqual(['/reports/report-1'])

    wrapper.unmount()
  })

  it('shows the age of each row relative to the time the page read the data', async () => {
    const { wrapper } = await mounting()

    expect(wrapper.get('[data-queue="assigned"]').text()).toContain('4 hours ago')
    expect(wrapper.get('[data-queue="reports"]').text()).toContain('3 hours ago')

    wrapper.unmount()
  })

  it('links each section to its filtered full list', async () => {
    const { wrapper } = await mounting()
    const showAll = (queue: string) => wrapper.get(`[data-queue="${queue}"] [data-testid="show-all"]`).attributes('href')

    expect(showAll('assigned')).toBe('/cases?mine=1')
    expect(showAll('urgent')).toBe('/cases?priority=URGENT')
    expect(showAll('appeals')).toBe('/appeals')
    expect(showAll('answers')).toBe('/appeals')
    expect(showAll('reports')).toBe('/reports')

    wrapper.unmount()
  })

  it('says what an empty assigned section is for', async () => {
    const { wrapper } = await mounting({ cases: fakeCaseList([]).read })

    expect(wrapper.get('[data-queue="assigned"]').text()).toContain('No case is assigned to you. Cases you take appear here.')

    wrapper.unmount()
  })

  it('says the account has no staff identity and reads no assigned cases instead of an empty list', async () => {
    const cases = fakeCaseList(MY_OPEN_CASES)
    const { wrapper } = await mounting({ cases: cases.read, oid: null })

    expect(wrapper.get('[data-queue="assigned"]').text()).toContain('Your account has no staff identity yet.')
    expect(wrapper.find('[data-queue="assigned"] [data-testid="section-count"]').exists()).toBe(false)
    expect(cases.queries.some((query) => query.assignee !== undefined)).toBe(false)

    wrapper.unmount()
  })

  it('fails one section alone: the other sections keep their rows', async () => {
    const failing: ReturnType<typeof fakeCaseList>['read'] = async () => fail('backend down', 503, 'UNKNOWN')
    const { wrapper } = await mounting({ cases: failing })

    expect(wrapper.get('[data-queue="assigned"] [role="alert"]').text()).toContain('backend down')
    expect(wrapper.find('[data-queue="urgent"] [role="alert"]').exists()).toBe(true)
    expect(rowHrefs(wrapper, 'appeals')).toEqual(['/appeals/appeal-1', '/appeals/appeal-2'])
    expect(wrapper.find('[data-queue="reports"] [role="alert"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('has exactly one level-one heading, the inbox title', async () => {
    const { wrapper } = await mounting()

    const headings = wrapper.findAll('h1')
    expect(headings).toHaveLength(1)
    expect(headings[0]!.text()).toBe('Inbox')

    wrapper.unmount()
  })

  it('shows the error of a failed inbox read with a retry that reads the inbox again', async () => {
    const inboxRead = vi
      .fn()
      .mockResolvedValueOnce(fail('backend down', 503, 'UNKNOWN'))
      .mockResolvedValueOnce(ok(inbox()))
    const { wrapper } = await mounting({ inbox: inboxRead })

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('[data-queue="appeals"] [data-testid="section-count"]').text()).toBe('4')
    expect(inboxRead).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('keeps the active punishments below the queue', async () => {
    const { wrapper } = await mounting()

    expect(wrapper.get('[data-testid="section-network"]').text()).toContain('12')

    wrapper.unmount()
  })

  describe('roles', () => {
    it('shows a viewer no action, only the read-only note', async () => {
      const { wrapper } = await mounting({ roles: ['VIEWER'] })

      expect(wrapper.findAll('button').filter((button) => ['Assign to me', 'Close'].includes(button.text()))).toHaveLength(0)
      expect(wrapper.text()).toContain('Read-only: moderators act on cases and appeals.')

      wrapper.unmount()
    })

    it('gives a moderator Close on the cases the caller holds and Assign to me on the unassigned ones', async () => {
      const cases = fakeCaseList([
        ...MY_OPEN_CASES,
        caseListItem({ shortId: 'FREE1', punishmentIdentifier: 'p-free', priority: 'URGENT' }),
      ])
      const { wrapper } = await mounting({ roles: ['MODERATOR'], cases: cases.read })

      expect(wrapper.get('[data-queue="assigned"]').findAll('button').map((button) => button.text())).toEqual(['Close', 'Close'])
      expect(wrapper.get('[data-queue="urgent"]').findAll('button').map((button) => button.text())).toEqual(['Assign to me'])
      expect(wrapper.text()).not.toContain('Read-only')

      wrapper.unmount()
    })

    it('gives an administrator the same actions as a moderator', async () => {
      const cases = fakeCaseList([caseListItem({ shortId: 'FREE2', punishmentIdentifier: 'p-free-2', priority: 'HIGH' })])
      const { wrapper } = await mounting({ roles: ['ADMIN'], cases: cases.read })

      expect(wrapper.get('[data-queue="urgent"]').findAll('button').map((button) => button.text())).toEqual(['Assign to me'])

      wrapper.unmount()
    })
  })

  describe('actions', () => {
    it('does not change anything when the page opens', async () => {
      const updateCase = vi.fn()
      const cases = fakeCaseList([caseListItem({ shortId: 'FREE3', punishmentIdentifier: 'p-free-3', priority: 'URGENT' }), ...MY_OPEN_CASES])
      const { wrapper } = await mounting({ roles: ['MODERATOR'], cases: cases.read, updateCase })

      expect(updateCase).not.toHaveBeenCalled()

      wrapper.unmount()
    })

    it('assigns the case to the caller with the version the row was read at', async () => {
      const updateCase = vi.fn(async () => ok(caseResponse({ punishmentIdentifier: 'p-free-4', version: 4, assigneeOid: ME })))
      const cases = fakeCaseList([caseListItem({ shortId: 'FREE4', punishmentIdentifier: 'p-free-4', priority: 'URGENT', version: 3 })])
      const { wrapper } = await mounting({ roles: ['MODERATOR'], cases: cases.read, updateCase })

      await wrapper.get('[data-queue="urgent"] button').trigger('click')
      await flushPromises()

      expect(updateCase).toHaveBeenCalledWith('p-free-4', { expectedVersion: 3, assigneeOid: ME })

      wrapper.unmount()
    })

    it('offers an undo for ten seconds after an assignment, and the undo sends the version of that assignment', async () => {
      const updateCase = vi.fn(async (_reference: string, change: { assigneeOid?: string | null }) =>
        ok(caseResponse({ punishmentIdentifier: 'p-free-5', version: change.assigneeOid === null ? 6 : 4 })),
      )
      const cases = fakeCaseList([caseListItem({ shortId: 'FREE5', punishmentIdentifier: 'p-free-5', priority: 'URGENT', version: 3 })])
      const { wrapper } = await mounting({ roles: ['MODERATOR'], cases: cases.read, updateCase })

      await wrapper.get('[data-queue="urgent"] button').trigger('click')
      await flushPromises()
      const undo = wrapper.findAll('button').find((button) => button.text() === 'Undo')
      expect(undo, 'an undo button after the assignment').toBeDefined()

      await undo!.trigger('click')
      await flushPromises()

      expect(updateCase).toHaveBeenLastCalledWith('p-free-5', { expectedVersion: 4, assigneeOid: null })

      wrapper.unmount()
    })

    it('stops offering the undo after ten seconds', async () => {
      const updateCase = vi.fn(async () => ok(caseResponse({ punishmentIdentifier: 'p-free-6', version: 4 })))
      const cases = fakeCaseList([caseListItem({ shortId: 'FREE6', punishmentIdentifier: 'p-free-6', priority: 'URGENT', version: 3 })])
      const { wrapper } = await mounting({ roles: ['MODERATOR'], cases: cases.read, updateCase })

      await wrapper.get('[data-queue="urgent"] button').trigger('click')
      await flushPromises()
      vi.advanceTimersByTime(9_999)
      expect(wrapper.findAll('button').some((button) => button.text() === 'Undo')).toBe(true)

      vi.advanceTimersByTime(1)
      await flushPromises()
      expect(wrapper.findAll('button').some((button) => button.text() === 'Undo')).toBe(false)

      wrapper.unmount()
    })

    it('asks before closing a case and sends the close only when confirmed', async () => {
      const updateCase = vi.fn(async () => ok(caseResponse({ punishmentIdentifier: 'p-mine-2', status: 'CLOSED', version: 4 })))
      const { wrapper } = await mounting({ roles: ['MODERATOR'], updateCase })

      // The first row is the oldest activity: MINE0002.
      await wrapper.get('[data-queue="assigned"] button').trigger('click')
      await flushPromises()
      expect(wrapper.get('[role="dialog"]').text()).toContain('MINE0002')
      expect(updateCase).not.toHaveBeenCalled()

      const confirm = wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === 'Close case')
      await confirm!.trigger('click')
      await flushPromises()

      expect(updateCase).toHaveBeenCalledWith('p-mine-2', { expectedVersion: 3, status: 'CLOSED' })

      wrapper.unmount()
    })

    it('does not close when the confirmation is cancelled', async () => {
      const updateCase = vi.fn()
      const { wrapper } = await mounting({ roles: ['MODERATOR'], updateCase })

      await wrapper.get('[data-queue="assigned"] button').trigger('click')
      await flushPromises()
      const cancel = wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === 'Cancel')
      await cancel!.trigger('click')
      await flushPromises()

      expect(updateCase).not.toHaveBeenCalled()

      wrapper.unmount()
    })

    it('tells the moderator when the case changed, and does not overwrite it', async () => {
      const updateCase = vi.fn(async () => fail('version is stale', 409, 'CONFLICT'))
      const { wrapper } = await mounting({ roles: ['MODERATOR'], updateCase })

      await wrapper.get('[data-queue="assigned"] button').trigger('click')
      await flushPromises()
      const confirm = wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === 'Close case')
      await confirm!.trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('The case changed since. Reload to see it.')

      wrapper.unmount()
    })
  })

  describe('links', () => {
    it('keeps the actions out of the links, so every row is one link and every action a button', async () => {
      const cases = fakeCaseList([caseListItem({ shortId: 'FREE7', punishmentIdentifier: 'p-free-7', priority: 'URGENT' })])
      const { wrapper } = await mounting({ roles: ['MODERATOR'], cases: cases.read })

      expect(wrapper.findAll('a button')).toHaveLength(0)
      expect(wrapper.get('[data-queue="urgent"] [data-testid="row"] a').attributes('href')).toBe('/cases/p-free-7')

      wrapper.unmount()
    })
  })
})

describe('InboxView requests', () => {
  // The number of case reads does not depend on how many cases there are: counts come from totalSize and
  // each list reads at most one page, so a busy inbox costs the same as a quiet one.
  const CASE_READS_ON_LOAD = 12

  it('makes the same fixed number of case reads on load, with few cases', async () => {
    const read = vi.fn(fakeCaseList(MY_OPEN_CASES).read)
    const { wrapper } = await mounting({ cases: read })

    expect(read).toHaveBeenCalledTimes(CASE_READS_ON_LOAD)

    wrapper.unmount()
  })

  it('makes the same fixed number of case reads on load, with many cases', async () => {
    const many = Array.from({ length: 450 }, (_, index) =>
      caseListItem({
        shortId: `BIG${index}`,
        punishmentIdentifier: `p-big-${index}`,
        priority: index % 2 === 0 ? 'URGENT' : 'HIGH',
        status: index % 3 === 0 ? 'OPEN' : index % 3 === 1 ? 'IN_REVIEW' : 'WAITING',
        assigneeOid: index % 5 === 0 ? ME : null,
        lastActivityAt: index,
      }),
    )
    const read = vi.fn(fakeCaseList(many).read)
    const { wrapper } = await mounting({ cases: read })

    expect(read).toHaveBeenCalledTimes(CASE_READS_ON_LOAD)
    expect(wrapper.get('[data-queue="assigned"] [data-testid="section-count"]').text()).toBe('90')

    wrapper.unmount()
  })

  it('shows a lower-bound count with a plus when the urgent read was full, and keeps Show all', async () => {
    const full = Array.from({ length: 101 }, (_, index) =>
      caseListItem({ shortId: `PL${index}`, punishmentIdentifier: `p-pl-${index}`, priority: 'URGENT', lastActivityAt: index }),
    )
    const { wrapper } = await mounting({ cases: fakeCaseList([...MY_OPEN_CASES, ...full]).read })

    expect(wrapper.get('[data-queue="urgent"] [data-testid="section-count"]').text()).toBe('100+')
    expect(wrapper.get('[data-queue="urgent"] [data-testid="show-all"]').attributes('href')).toBe('/cases?priority=URGENT')
    expect(wrapper.get('[data-queue="assigned"] [data-testid="section-count"]').text()).toBe('2')

    wrapper.unmount()
  })
})

describe('InboxView: changed by others', () => {
  const section = (queue = 'changed') => `[data-queue="${queue}"]`

  it('shows each change in words, with who made it and how long ago', async () => {
    const { wrapper } = await mounting({
      changedByOthers: async () =>
        ok(changedSection([changedItem({ change: 'STATUS', byName: 'Anna', at: NOW - 2 * HOUR })])),
    })

    const text = wrapper.get(section()).text()
    expect(text).toContain('Status changed')
    expect(text).toContain('by Anna')
    expect(text).toContain('2 hours ago')

    wrapper.unmount()
  })

  it('names a change of an anonymized staff member as a former staff member', async () => {
    const { wrapper } = await mounting({
      // The backend sends null once the name is anonymized; the generated type only says the name may be missing.
      changedByOthers: async () => ok(changedSection([{ ...changedItem(), byName: null } as unknown as InboxChangedItem])),
    })

    expect(wrapper.get(section()).text()).toContain('by a former staff member')

    wrapper.unmount()
  })

  it('links a changed case to its page and a changed appeal to the appeal page', async () => {
    const { wrapper } = await mounting({
      changedByOthers: async () =>
        ok(
          changedSection([
            changedItem({ kind: 'CASE', id: 'AB12CD34' }),
            changedItem({ kind: 'APPEAL', id: 'APP00009', reference: 'AB12CD34', change: 'APPEAL_DECIDED' }),
          ]),
        ),
    })

    expect(rowHrefs(wrapper, 'changed')).toEqual(['/cases/AB12CD34', '/appeals/APP00009'])

    wrapper.unmount()
  })

  it('says what the change was for an appeal and for a change the panel does not know', async () => {
    const { wrapper } = await mounting({
      changedByOthers: async () =>
        ok(
          changedSection([
            changedItem({ kind: 'APPEAL', id: 'APP00009', change: 'APPEAL_FILED' }),
            // A change a newer backend may send, which the generated enum does not list yet.
            changedItem({ id: 'ZZ99YY88', change: 'SOMETHING_NEW' as InboxChangedItem['change'] }),
          ]),
        ),
    })

    const text = wrapper.get(section()).text()
    expect(text).toContain('Appeal filed')
    expect(text).toContain('Changed')

    wrapper.unmount()
  })

  it('shows the full count of changes, and at most ten rows', async () => {
    const many = Array.from({ length: 12 }, (_, index) => changedItem({ id: `CH${index}`, at: NOW - index * HOUR }))
    const { wrapper } = await mounting({
      changedByOthers: async () => ok(changedSection(many.slice(0, 10), 12)),
    })

    expect(wrapper.get(`${section()} [data-testid="section-count"]`).text()).toBe('12')
    expect(wrapper.get(section()).findAll('[data-testid="row"]')).toHaveLength(10)

    wrapper.unmount()
  })

  it('says what the section is for when nobody else changed anything', async () => {
    const { wrapper } = await mounting({ changedByOthers: async () => ok(changedSection()) })

    expect(wrapper.get(section()).text()).toContain('No case or appeal changed by another staff member in the last 24 hours.')

    wrapper.unmount()
  })

  it('fails its own section alone: the other sections keep their rows', async () => {
    const { wrapper } = await mounting({
      changedByOthers: async () => fail('backend down', 503, 'UNKNOWN'),
    })

    expect(wrapper.get(`${section()} [role="alert"]`).text()).toContain('backend down')
    expect(wrapper.find(`${section('appeals')} [role="alert"]`).exists()).toBe(false)
    expect(rowHrefs(wrapper, 'appeals')).toEqual(['/appeals/appeal-1', '/appeals/appeal-2'])

    wrapper.unmount()
  })

  it('retries only its own section', async () => {
    const read = vi
      .fn()
      .mockResolvedValueOnce(fail('backend down', 503, 'UNKNOWN'))
      .mockResolvedValueOnce(ok(changedSection([changedItem()])))
    const { wrapper } = await mounting({ changedByOthers: read })

    await wrapper.get(`${section()} [data-testid="retry"]`).trigger('click')
    await flushPromises()

    expect(wrapper.find(`${section()} [role="alert"]`).exists()).toBe(false)
    expect(rowHrefs(wrapper, 'changed')).toEqual(['/cases/AB12CD34'])
    expect(read).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('offers no action in the section, for a moderator or a viewer', async () => {
    const { wrapper } = await mounting({
      roles: ['VIEWER'],
      changedByOthers: async () => ok(changedSection([changedItem()])),
    })

    expect(wrapper.get(section()).findAll('button')).toHaveLength(0)
    expect(rowHrefs(wrapper, 'changed')).toEqual(['/cases/AB12CD34'])

    wrapper.unmount()
  })
})
