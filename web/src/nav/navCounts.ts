import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { BlackholeApi } from '../api/blackhole'
import type { InboxSummary } from '../api/types'

/** What the navigation counts. */
export type CountKey = 'inbox' | 'cases' | 'appeals' | 'reports'

/** Above this the badge reads "99+". */
export const COUNT_CAP = 99

/** The text of a badge: the number, capped; nothing at 0 or while unknown. */
export function badgeText(count: number | null | undefined): string | null {
  if (!count || count < 1) return null
  return count > COUNT_CAP ? `${COUNT_CAP}+` : String(count)
}

/**
 * The inbox total: open appeals, new reports and unassigned cases. Items that need an answer are a part of
 * the open appeals, so they are not added again.
 */
export function inboxTotal(inbox: InboxSummary): number {
  return inbox.openAppeals.count + inbox.newReports.count + inbox.unassignedCases.count
}

/**
 * Open work for the navigation badges, held in memory. Appeals, reports and the inbox total come from the
 * inbox read, so the badges cannot disagree with the inbox page; open cases come from the totalSize of a
 * one-item case page, so no list is downloaded. A failed request keeps the previous value: a badge is a hint,
 * not an error.
 */
export const useNavCountsStore = defineStore('navCounts', () => {
  const inbox = ref<number | null>(null)
  const cases = ref<number | null>(null)
  const appeals = ref<number | null>(null)
  const reports = ref<number | null>(null)
  let inFlight: Promise<void> | null = null

  async function load(api: BlackholeApi): Promise<void> {
    try {
      const [summary, openCases] = await Promise.all([api.inbox(), api.cases({ status: 'OPEN', page: 0, size: 1 })])
      if (summary.ok) {
        inbox.value = inboxTotal(summary.data)
        appeals.value = summary.data.openAppeals.count
        reports.value = summary.data.newReports.count
      }
      if (openCases.ok) cases.value = openCases.data.totalSize
    } catch {
      // Keep what is shown.
    }
  }

  /** One refresh at a time: a navigation right after a timer tick shares the request. */
  function refresh(api: BlackholeApi): Promise<void> {
    if (inFlight) return inFlight
    const run = load(api).finally(() => {
      inFlight = null
    })
    inFlight = run
    return run
  }

  function reset(): void {
    inbox.value = cases.value = appeals.value = reports.value = null
  }

  return { inbox, cases, appeals, reports, refresh, reset }
})
