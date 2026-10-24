import type { CaseTimelineItem } from '../../api/types'

/** Material Symbols-style paths (24dp), decorative: the label always says what the event is. */
const ICON = {
  gavel: 'M1 21h12v2H1zM5.245 8.07l2.83-2.827 14.14 14.142-2.828 2.828zM12.317 1l5.657 5.656-2.83 2.83-5.654-5.66zM3.825 9.485l5.657 5.657-2.828 2.828-5.657-5.657z',
  undo: 'M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z',
  hourglass: 'M6 2v6h.01L6 8.01 10 12l-4 4 .01.01H6V22h12v-5.99h-.01L18 16l-4-4 4-3.99-.01-.01H18V2H6zm10 14.5V20H8v-3.5l4-4 4 4zm-4-5l-4-4V4h8v3.5l-4 4z',
  attach: 'M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5c0-1.38 1.12-2.5 2.5-2.5s2.5 1.12 2.5 2.5v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5c0 1.38 1.12 2.5 2.5 2.5s2.5-1.12 2.5-2.5V5c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z',
  flag: 'M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6z',
  swap: 'M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z',
  person: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  label: 'M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z',
  priority: 'M12 3c-.55 0-1 .45-1 1v9c0 .55.45 1 1 1s1-.45 1-1V4c0-.55-.45-1-1-1zm0 14a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z',
  note: 'M3 18h12v-2H3v2zM3 6v2h18V6H3zm0 7h18v-2H3v2z',
  edit: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
  delete: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
  help: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 17h-2v-2h2v2zm2.07-7.75l-.9.92C13.45 12.9 13 13.5 13 15h-2v-.5c0-1.1.45-2.1 1.17-2.83l1.24-1.26c.37-.36.59-.86.59-1.41 0-1.1-.9-2-2-2s-2 .9-2 2H8c0-2.21 1.79-4 4-4s4 1.79 4 4c0 .88-.36 1.68-.93 2.25z',
  verified: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z',
  warning: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
  system: 'M15 9H9v6h6V9zm-2 4h-2v-2h2v2zm8-2V9h-2V7c0-1.1-.9-2-2-2h-2V3h-2v2h-2V3H9v2H7c-1.1 0-2 .9-2 2v2H3v2h2v2H3v2h2v2c0 1.1.9 2 2 2h2v2h2v-2h2v2h2v-2h2c1.1 0 2-.9 2-2v-2h2v-2h-2v-2h2zm-4 6H7V7h10v10z',
} as const

export type IconName = keyof typeof ICON
export const iconPath = (name: IconName): string => ICON[name]

interface EventInfo {
  /** Key below `case.events` */
  key: string
  icon: IconName
}

const KNOWN: Record<string, EventInfo> = {
  'punishment.applied': { key: 'punishmentApplied', icon: 'gavel' },
  'punishment.revoked': { key: 'punishmentRevoked', icon: 'undo' },
  'punishment.lifted': { key: 'punishmentLifted', icon: 'undo' },
  'punishment.retemplated': { key: 'punishmentRetemplated', icon: 'label' },
  'punishment.expired': { key: 'punishmentExpired', icon: 'hourglass' },
  'evidence.attached': { key: 'evidenceAttached', icon: 'attach' },
  'evidence.lifted': { key: 'evidenceLifted', icon: 'attach' },
  'evidence.revoked': { key: 'evidenceRevoked', icon: 'attach' },
  'appeal.filed': { key: 'appealFiled', icon: 'flag' },
  'appeal.decided': { key: 'appealDecided', icon: 'gavel' },
  'case.status_changed': { key: 'statusChanged', icon: 'swap' },
  'case.assignee_changed': { key: 'assigneeChanged', icon: 'person' },
  'case.tags_changed': { key: 'tagsChanged', icon: 'label' },
  'case.priority_changed': { key: 'priorityChanged', icon: 'priority' },
  'note.added': { key: 'noteAdded', icon: 'note' },
  'note.edited': { key: 'noteEdited', icon: 'edit' },
  'note.redacted': { key: 'noteRedacted', icon: 'delete' },
}

/** Unknown types (a newer backend, a reserved portal type) are shown generically, never dropped. */
const GENERIC: EventInfo = { key: 'unknown', icon: 'help' }

export function eventInfo(type: string): EventInfo & { known: boolean } {
  const info = KNOWN[type]
  return info ? { ...info, known: true } : { ...GENERIC, known: false }
}

export const isNoteVersion = (event: CaseTimelineItem): boolean => event.type === 'note.added' || event.type === 'note.edited'

/** The timeline groups the backend accepts as a type filter. */
export const TYPE_GROUPS = ['punishment', 'appeal', 'evidence', 'case', 'note'] as const

/** Payload values by key; the payload is typed loosely because unknown types carry anything. */
export function field(event: CaseTimelineItem, key: string): unknown {
  return (event.payload as Record<string, unknown> | undefined)?.[key]
}

export const text = (value: unknown): string | null => (typeof value === 'string' && value !== '' ? value : null)
export const num = (value: unknown): number | null => (typeof value === 'number' ? value : null)
export const list = (value: unknown): string[] => (Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [])

/** The id of the note an event belongs to. */
export const noteIdOf = (event: CaseTimelineItem): string | null => event.subjectId ?? text(field(event, 'noteId'))

/** A redacted version has no text any more (the backend empties the payload and sets `redactedAt`). */
export const isRedacted = (event: CaseTimelineItem): boolean => event.redactedAt != null || (isNoteVersion(event) && text(field(event, 'text')) === null)

/** The backend rejects longer notes. */
export const MAX_CASE_NOTE_LENGTH = 4000
