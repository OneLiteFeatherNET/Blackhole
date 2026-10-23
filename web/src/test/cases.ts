import type { Case, CaseTimelineItem, CaseTimelinePage } from '../api/types'

/** The signed-in user of `piniaWithRoles`. */
export const ME = '00000000-0000-4000-8000-0000000000a1'
export const OTHER = '00000000-0000-4000-8000-0000000000b2'
export const NOTE = '3f2b8c1e-0000-4000-8000-000000000001'

/** A case with sensible defaults. */
export function caseResponse(overrides: Partial<Case> = {}): Case {
  return {
    punishmentIdentifier: 'p-0001',
    shortId: '#AB12CD',
    ownerPrefix: 'abcdef12',
    section: 'network',
    punishmentState: 'ACTIVE',
    permanent: false,
    punishmentCreatedAt: Date.UTC(2026, 6, 1, 12, 0),
    status: 'OPEN',
    priority: 'NORMAL',
    tags: [],
    version: 3,
    related: [],
    ...overrides,
  }
}

type Overrides = Omit<Partial<CaseTimelineItem>, 'payload'> & { payload?: Record<string, unknown> }

let counter = 0

/** A timeline event; every call gets the next `seq` unless one is given. */
export function timelineItem(overrides: Overrides = {}): CaseTimelineItem {
  counter += 1
  return {
    seq: counter,
    identifier: `00000000-0000-4000-8000-${String(counter).padStart(12, '0')}`,
    type: 'punishment.applied',
    payloadVersion: 1,
    occurredAt: Date.UTC(2026, 6, 1, 12, counter),
    origin: 'LIVE',
    visibility: 'STAFF',
    actor: { oid: OTHER, name: 'Ada', trust: 'VERIFIED' },
    payload: {},
    ...(overrides as Partial<CaseTimelineItem>),
  }
}

export function timelinePage(items: CaseTimelineItem[], nextCursor: string | null = null): CaseTimelinePage {
  return { items, nextCursor }
}
