// Reopening a case returns it to the status it had before it was closed (`restructure-staff-panel`, decision 4). That
// status is the `from` of the newest status change to CLOSED in the case timeline. Without one, the case reopens to OPEN.
import type { BlackholeApi } from '../../api/blackhole'
import type { CaseStatus, CaseTimelineItem } from '../../api/types'
import { field, text } from './caseEvents'

/** The statuses a case can return to from CLOSED: the three open ones. */
const REOPENABLE: readonly CaseStatus[] = ['OPEN', 'IN_REVIEW', 'WAITING']

/** The timeline is read newest first, at most this many pages, so a long case cannot hold the dialog for ever. */
const MAX_PAGES = 5

interface CloseScan {
  /** True when a change to CLOSED is among the events. */
  closed: boolean
  /** The status before that close, or null when the timeline does not name a status a case can return to. */
  status: CaseStatus | null
}

function scan(events: CaseTimelineItem[]): CloseScan {
  const close = events.find((event) => event.type === 'case.status_changed' && text(field(event, 'to')) === 'CLOSED')
  if (!close) return { closed: false, status: null }
  const from = text(field(close, 'from'))
  const status = REOPENABLE.find((candidate) => candidate === from) ?? null
  return { closed: true, status }
}

/** The status before the newest close among events read newest first, or null when there is none. */
export function statusBeforeClose(events: CaseTimelineItem[]): CaseStatus | null {
  return scan(events).status
}

export interface ReopenTarget {
  status: CaseStatus
  /** False when no earlier status was found and the case reopens to OPEN by default. */
  found: boolean
}

/** Reads the case status changes (newest first) until the close is found; the default is OPEN. */
export async function reopenTarget(api: BlackholeApi, reference: string): Promise<ReopenTarget> {
  let cursor: string | undefined
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const result = await api.caseTimeline(reference, { types: ['case'], order: 'desc', size: 100, cursor })
    if (!result.ok) break
    const found = scan(result.data.items)
    if (found.closed) return found.status ? { status: found.status, found: true } : { status: 'OPEN', found: false }
    if (!result.data.nextCursor) break
    cursor = result.data.nextCursor
  }
  return { status: 'OPEN', found: false }
}
