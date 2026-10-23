import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { BlackholeApi } from '../api/blackhole'

/** What the navigation counts. */
export type CountKey = 'cases' | 'appeals' | 'reports'

/** Above this the badge reads "99+". */
export const COUNT_CAP = 99

/** The text of a badge: the number, capped; nothing at 0 or while unknown. */
export function badgeText(count: number | null | undefined): string | null {
  if (!count || count < 1) return null
  return count > COUNT_CAP ? `${COUNT_CAP}+` : String(count)
}

/**
 * Open work for the navigation badges, held in memory. Appeals and reports come from the dashboard
 * summary (`pendingAppeals`, `openReports`), open cases from the totalSize of a one-item case page, so
 * no list is downloaded. A failed request keeps the previous value: a badge is a hint, not an error.
 */
export const useNavCountsStore = defineStore('navCounts', () => {
  const cases = ref<number | null>(null)
  const appeals = ref<number | null>(null)
  const reports = ref<number | null>(null)
  let inFlight: Promise<void> | null = null

  async function load(api: BlackholeApi): Promise<void> {
    try {
      const [summary, openCases] = await Promise.all([api.dashboard(), api.cases({ status: 'OPEN', page: 0, size: 1 })])
      if (summary.ok) {
        appeals.value = summary.data.pendingAppeals ?? 0
        reports.value = summary.data.openReports ?? 0
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
    cases.value = appeals.value = reports.value = null
  }

  return { cases, appeals, reports, refresh, reset }
})
