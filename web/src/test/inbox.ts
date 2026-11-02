import type { ApiResult, CaseListItem, CaseListPage, CaseListQuery, CasePriority, CaseStatus } from '../api/types'

/** A case row of the case list with sensible defaults. */
export function caseListItem(overrides: Partial<CaseListItem> = {}): CaseListItem {
  return {
    punishmentIdentifier: 'p-0001',
    shortId: 'AB12CD34',
    section: 'network',
    punishmentState: 'ACTIVE',
    status: 'OPEN' as CaseStatus,
    assigneeOid: null,
    assigneeName: null,
    priority: 'NORMAL' as CasePriority,
    tags: [],
    punishmentCreatedAt: Date.UTC(2030, 0, 1, 9, 0),
    lastActivityAt: Date.UTC(2030, 0, 1, 9, 0),
    playerName: 'Steve',
    ownerShort: 'abcdef12',
    version: 3,
    ...overrides,
  }
}

/**
 * A `GET /case` that filters by the query like the server does (status, assignee, priority) and pages with
 * the given page size. Every query it receives is recorded, so a test can check what was asked.
 */
export function fakeCaseList(items: CaseListItem[], pageSize = 100) {
  const queries: CaseListQuery[] = []
  const read = async (query: CaseListQuery = {}): Promise<ApiResult<CaseListPage>> => {
    queries.push(query)
    const matching = items.filter(
      (item) =>
        (query.status === undefined || item.status === query.status) &&
        (query.assignee === undefined || item.assigneeOid === query.assignee) &&
        (query.priority === undefined || item.priority === query.priority),
    )
    const page = query.page ?? 0
    const totalPages = Math.ceil(matching.length / pageSize)
    const slice = matching.slice(page * pageSize, (page + 1) * pageSize)
    return {
      ok: true,
      data: { items: slice, page, size: pageSize, totalSize: matching.length, totalPages },
    }
  }
  return { read, queries }
}
