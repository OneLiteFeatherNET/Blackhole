import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fail, mockApi, ok } from '../test/mount'
import { badgeText, useNavCountsStore } from './navCounts'

const page = (totalSize: number) => ({ items: [], page: 0, size: 1, totalSize, totalPages: totalSize })
const summary = (extra: Record<string, number | undefined>) => ({ activePunishmentsBySection: [], latestEscalations: [], ...extra })

describe('badgeText', () => {
  it.each([
    [0, null],
    [null, null],
    [undefined, null],
    [1, '1'],
    [99, '99'],
    [100, '99+'],
    [1234, '99+'],
  ])('shows %s as %s', (count, expected) => {
    expect(badgeText(count)).toBe(expected)
  })
})

describe('nav counts store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('takes appeals and reports from the dashboard summary and open cases from the size of a one-item case page', async () => {
    const cases = vi.fn(async () => ok(page(7)))
    const api = mockApi({ dashboard: async () => ok(summary({ pendingAppeals: 3, openReports: 5 })), cases })
    const counts = useNavCountsStore()

    await counts.refresh(api)

    expect([counts.cases, counts.appeals, counts.reports]).toEqual([7, 3, 5])
    expect(cases).toHaveBeenCalledWith({ status: 'OPEN', page: 0, size: 1 })
  })

  it('reads counts the summary leaves out as zero', async () => {
    const api = mockApi({ dashboard: async () => ok(summary({})), cases: async () => ok(page(0)) })
    const counts = useNavCountsStore()

    await counts.refresh(api)

    expect([counts.cases, counts.appeals, counts.reports]).toEqual([0, 0, 0])
  })

  it('keeps the previous numbers when a request fails', async () => {
    const counts = useNavCountsStore()
    await counts.refresh(mockApi({ dashboard: async () => ok(summary({ pendingAppeals: 3, openReports: 5 })), cases: async () => ok(page(7)) }))

    await counts.refresh(mockApi({ dashboard: async () => fail('down', 500), cases: async () => fail('down', 500) }))

    expect([counts.cases, counts.appeals, counts.reports]).toEqual([7, 3, 5])
  })

  it('keeps the previous numbers when a request throws', async () => {
    const counts = useNavCountsStore()
    await counts.refresh(mockApi({ dashboard: async () => ok(summary({ pendingAppeals: 3 })), cases: async () => ok(page(7)) }))

    await counts.refresh(mockApi({ dashboard: async () => Promise.reject(new Error('network')), cases: async () => ok(page(9)) }))

    expect(counts.appeals).toBe(3)
  })

  it('shares one request between refreshes that overlap', async () => {
    const dashboard = vi.fn(async () => ok(summary({})))
    const api = mockApi({ dashboard, cases: async () => ok(page(0)) })
    const counts = useNavCountsStore()

    await Promise.all([counts.refresh(api), counts.refresh(api)])
    await counts.refresh(api)

    expect(dashboard).toHaveBeenCalledTimes(2)
  })
})
