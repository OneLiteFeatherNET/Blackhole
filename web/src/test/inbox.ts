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
 * A `GET /case` that filters by the query like the server does (status, assignee, priority) and answers
 * the page and size asked for (the default size is `defaultSize`). Every query it receives is recorded, so
 * a test can check what was asked and how many requests were made.
 */
export function fakeCaseList(items: CaseListItem[], defaultSize = 100) {
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
    const size = query.size ?? defaultSize
    const totalPages = Math.ceil(matching.length / size)
    const slice = matching.slice(page * size, (page + 1) * size)
    return {
      ok: true,
      data: { items: slice, page, size, totalSize: matching.length, totalPages },
    }
  }
  return { read, queries }
}
