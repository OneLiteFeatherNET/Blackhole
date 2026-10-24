import { describe, expect, it } from 'vitest'
import de from '../../locales/de.json'
import en from '../../locales/en.json'
import { eventInfo, TYPE_GROUPS } from './caseEvents'

function keys(node: unknown, prefix = ''): string[] {
  if (typeof node !== 'object' || node === null) return [prefix]
  return Object.entries(node).flatMap(([key, value]) => keys(value, prefix ? `${prefix}.${key}` : key))
}

// Every type the backend writes today has its own label; anything else gets the generic one.
const KNOWN_TYPES = [
  'punishment.applied', 'punishment.revoked', 'punishment.lifted', 'punishment.retemplated', 'punishment.expired',
  'evidence.attached', 'evidence.lifted', 'evidence.revoked',
  'appeal.filed', 'appeal.decided',
  'case.status_changed', 'case.assignee_changed', 'case.tags_changed', 'case.priority_changed',
  'note.added', 'note.edited', 'note.redacted',
  'message.added', 'message.edited', 'message.deleted', 'message.redacted',
  'ban_id.leak_marked', 'ban_id.reissued', 'transcript.exported',
  'protocol.stored', 'ticket.opened', 'ticket.transcript_complete', 'ticket.archived', 'ticket.channel_deleted',
]

describe('case strings', () => {
  it('has the same keys in German and English', () => {
    expect(keys(de.case).sort()).toEqual(keys(en.case).sort())
  })

  it.each(['en', 'de'] as const)('has a label for every known event type in %s', (language) => {
    const events = (language === 'en' ? en : de).case.events as Record<string, string>
    for (const type of KNOWN_TYPES) {
      const info = eventInfo(type)
      expect(info.known, type).toBe(true)
      expect(events[info.key], `${language} ${type}`).toBeTruthy()
    }
    expect(events.unknown).toBeTruthy()
  })

  it.each(['en', 'de'] as const)('names every filter group in %s', (language) => {
    const groups = (language === 'en' ? en : de).case.timeline.group as Record<string, string>
    for (const group of TYPE_GROUPS) expect(groups[group], group).toBeTruthy()
  })

  it('uses real umlauts in the German texts', () => {
    const text = JSON.stringify(de.case)
    expect(text).toContain('Fälle')
    expect(text).not.toMatch(/Faelle|ae?nderung|Loesch|fuer |ueber /)
  })

  it('treats a type it does not know as generic', () => {
    expect(eventInfo('portal.something_new')).toMatchObject({ known: false, key: 'unknown', icon: 'help' })
  })
})
