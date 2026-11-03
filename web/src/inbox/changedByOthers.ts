import type { InboxChangedItem } from '../api/types'

// The i18n key of the words for each change the backend names. A change the panel does not know yet, from a newer
// backend, falls back to the generic "changed" text instead of showing the raw enum value.
const CHANGE_TEXT_KEYS = new Map<string, string>([
  ['STATUS', 'inbox.changed.changes.status'],
  ['ASSIGNEE', 'inbox.changed.changes.assignee'],
  ['PRIORITY', 'inbox.changed.changes.priority'],
  ['TAGS', 'inbox.changed.changes.tags'],
  ['NOTE', 'inbox.changed.changes.note'],
  ['MESSAGE', 'inbox.changed.changes.message'],
  ['APPEAL_FILED', 'inbox.changed.changes.appealFiled'],
  ['APPEAL_DECIDED', 'inbox.changed.changes.appealDecided'],
  ['OTHER', 'inbox.changed.changes.other'],
])

export function changeTextKey(change: string): string {
  return CHANGE_TEXT_KEYS.get(change) ?? 'inbox.changed.changes.other'
}

/** A changed case opens its case page by the short reference, a changed appeal its appeal page. */
export function changedItemHref(item: InboxChangedItem): string {
  return item.kind === 'APPEAL' ? `/appeals/${item.id}` : `/cases/${item.id}`
}
