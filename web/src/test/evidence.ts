import type { Evidence, EvidencePage } from '../api/types'

/** The generated type of a payload is a map of empty objects; tests write the real, loosely typed content. */
type Overrides = Omit<Partial<Evidence>, 'payload'> & { payload?: Record<string, unknown> }

/** An evidence item with sensible defaults; every call gets a fresh identifier. */
export function evidenceItem(overrides: Overrides = {}): Evidence {
  return {
    identifier: crypto.randomUUID(),
    owner: 'ab'.repeat(64),
    kind: 'LINK',
    sourceSystem: 'anticheat-x',
    createdAt: Date.UTC(2026, 6, 1, 12, 0),
    status: 'VALID',
    provenance: 'STAFF',
    verified: true,
    ...(overrides as Partial<Evidence>),
  }
}

export function evidencePage(items: Evidence[], extra: Partial<EvidencePage> = {}): EvidencePage {
  return { items, page: 0, size: 20, totalSize: items.length, totalPages: 1, ...extra }
}
