import type { BlackholeApi } from '../api/blackhole'
import type { ApiResult, CaseListItem, CasePriority, CaseListQuery, CaseStatus } from '../api/types'

/** The statuses that are not CLOSED: the case list has no "not closed" filter, so each one is asked for. */
export const OPEN_STATUSES: CaseStatus[] = ['OPEN', 'IN_REVIEW', 'WAITING']
/** The priorities that count as urgent in the inbox. */
export const URGENT_PRIORITIES: CasePriority[] = ['URGENT', 'HIGH']
/** The page size asked for; the server may answer with a smaller one, the paging follows its totalPages. */
export const PAGE_SIZE = 100
/** The most pages read for one query. Reaching it marks the section's count as truncated. */
export const MAX_PAGES = 50

export interface CaseQueue {
  /** How many cases the section holds. Exact unless `truncated`. */
  count: number
  /** True when the page bound stopped the reading, so `count` is a lower bound. */
  truncated: boolean
  /** Every case of the section in the order the section shows them; the view takes the first rows. */
  items: CaseListItem[]
}

type Read = Pick<BlackholeApi, 'cases'>['cases']

interface Collected {
  items: CaseListItem[]
  truncated: boolean
}

/** Reads every page of one query, up to MAX_PAGES. */
async function collect(read: Read, query: CaseListQuery): Promise<ApiResult<Collected>> {
  const items: CaseListItem[] = []
  let page = 0
  let totalPages = 1
  while (page < totalPages && page < MAX_PAGES) {
    const result = await read({ ...query, page, size: PAGE_SIZE })
    if (!result.ok) return result
    items.push(...result.data.items)
    totalPages = result.data.totalPages
    page += 1
  }
  return { ok: true, data: { items, truncated: page < totalPages } }
}

/** Runs the queries together; the first failure fails the whole section, so no partial count is shown. */
async function collectAll(read: Read, queries: CaseListQuery[]): Promise<ApiResult<Collected>> {
  const results = await Promise.all(queries.map((query) => collect(read, query)))
  const failed = results.find((result) => !result.ok)
  if (failed) return failed
  const items = results.flatMap((result) => (result.ok ? result.data.items : []))
  return { ok: true, data: { items, truncated: results.some((result) => result.ok && result.data.truncated) } }
}

const PRIORITY_RANK: Record<CasePriority, number> = { URGENT: 0, HIGH: 1, NORMAL: 2, LOW: 3 }

function oldestActivityFirst(a: CaseListItem, b: CaseListItem): number {
  return a.lastActivityAt - b.lastActivityAt
}

/** The open cases the caller holds, oldest activity first. */
export async function assignedCases(api: Pick<BlackholeApi, 'cases'>, oid: string): Promise<ApiResult<CaseQueue>> {
  const result = await collectAll(api.cases, OPEN_STATUSES.map((status) => ({ assignee: oid, status })))
  if (!result.ok) return result
  const items = [...result.data.items].sort(oldestActivityFirst)
  return { ok: true, data: { count: items.length, truncated: result.data.truncated, items } }
}

/**
 * The open URGENT and HIGH cases that nobody holds, URGENT first, then oldest activity first. The case list
 * cannot filter for "no assignee", so the rows that have one are dropped here, after reading.
 */
export async function unassignedUrgentCases(api: Pick<BlackholeApi, 'cases'>): Promise<ApiResult<CaseQueue>> {
  const queries = URGENT_PRIORITIES.flatMap((priority) => OPEN_STATUSES.map((status) => ({ priority, status })))
  const result = await collectAll(api.cases, queries)
  if (!result.ok) return result
  const items = result.data.items
    .filter((item) => !item.assigneeOid)
    .sort((a, b) => PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority] || oldestActivityFirst(a, b))
  return { ok: true, data: { count: items.length, truncated: result.data.truncated, items } }
}
