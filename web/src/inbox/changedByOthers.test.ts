import { describe, expect, it } from 'vitest'
import de from '../locales/de.json'
import en from '../locales/en.json'
import { changedItemHref, changeTextKey } from './changedByOthers'

function lookup(messages: unknown, key: string): unknown {
  return key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], messages)
}

describe('changeTextKey', () => {
  it.each([
    ['STATUS', 'inbox.changed.changes.status'],
    ['ASSIGNEE', 'inbox.changed.changes.assignee'],
    ['PRIORITY', 'inbox.changed.changes.priority'],
    ['TAGS', 'inbox.changed.changes.tags'],
    ['NOTE', 'inbox.changed.changes.note'],
    ['MESSAGE', 'inbox.changed.changes.message'],
    ['APPEAL_FILED', 'inbox.changed.changes.appealFiled'],
    ['APPEAL_DECIDED', 'inbox.changed.changes.appealDecided'],
    ['OTHER', 'inbox.changed.changes.other'],
  ])('names the change %s with its own text', (change, key) => {
    expect(changeTextKey(change)).toBe(key)
  })

  it('falls back to the generic text for a change the panel does not know yet', () => {
    expect(changeTextKey('SOMETHING_NEW')).toBe('inbox.changed.changes.other')
  })

  it('does not treat an object property name as a known change', () => {
    expect(changeTextKey('constructor')).toBe('inbox.changed.changes.other')
  })

  it.each(['en', 'de'] as const)('has every change text in the %s texts', (language) => {
    const messages = language === 'en' ? en : de
    const keys = ['status', 'assignee', 'priority', 'tags', 'note', 'message', 'appealFiled', 'appealDecided', 'other']

    for (const key of keys) {
      expect(lookup(messages, `inbox.changed.changes.${key}`), `${key} in ${language}`).toEqual(expect.any(String))
    }
  })
})

describe('changedItemHref', () => {
  it('links a changed case to its page by the short reference', () => {
    expect(changedItemHref({ kind: 'CASE', id: 'AB12CD34', change: 'STATUS', at: 1 })).toBe('/cases/AB12CD34')
  })

  it('links a changed appeal to its page by the appeal identifier', () => {
    expect(changedItemHref({ kind: 'APPEAL', id: 'APP00001', change: 'APPEAL_DECIDED', at: 1 })).toBe('/appeals/APP00001')
  })
})
