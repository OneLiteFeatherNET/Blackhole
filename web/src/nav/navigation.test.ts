import { describe, expect, it } from 'vitest'
import { i18n } from '../i18n'
import { visibleGroups } from './navigation'

const ADMIN = { hasAccess: true, administrator: true }
const STAFF = { hasAccess: true, administrator: false }

/** Group key, whether it has a heading, and the destination keys, for what the access offers. */
function shape(access: { hasAccess: boolean; administrator: boolean }) {
  return visibleGroups(access).map((group) => [group.key, group.heading, group.items.map((item) => item.key)])
}

/** A translated label in the given locale; the locale is restored by each test that changes it. */
function label(key: string, locale: 'en' | 'de'): string {
  i18n.global.locale.value = locale
  return i18n.global.t(key)
}

describe('visibleGroups', () => {
  it('offers nothing to a user without access', () => {
    expect(visibleGroups({ hasAccess: false, administrator: false })).toEqual([])
  })

  it('offers the administration page and its group to an administrator', () => {
    const groups = visibleGroups(ADMIN)

    expect(groups.flatMap((group) => group.items.map((item) => item.to))).toContain('/admin')
    expect(groups.map((group) => group.key)).toContain('administration')
  })

  it('hides the administration page and its then empty group from other roles', () => {
    const groups = visibleGroups(STAFF)

    expect(groups.flatMap((group) => group.items.map((item) => item.to))).not.toContain('/admin')
    expect(groups.map((group) => group.key)).not.toContain('administration')
  })

  it('lists the inbox first, without a heading, then four headed groups', () => {
    expect(shape(ADMIN)).toEqual([
      ['home', false, ['inbox']],
      ['work', true, ['cases', 'appeals', 'reports']],
      ['players', true, ['playerLookup', 'activePunishments']],
      ['configuration', true, ['sections', 'templates', 'appealTerms', 'clientRules']],
      ['administration', true, ['administration']],
    ])
  })

  it('keeps the groups of a staff member and drops only the administration group', () => {
    expect(shape(STAFF).map(([key]) => key)).toEqual(['home', 'work', 'players', 'configuration'])
  })

  it('routes every destination to its page, the inbox at the root', () => {
    const routes = visibleGroups(ADMIN).flatMap((group) => group.items.map((item) => [item.key, item.to]))

    expect(routes).toEqual([
      ['inbox', '/'],
      ['cases', '/cases'],
      ['appeals', '/appeals'],
      ['reports', '/reports'],
      ['playerLookup', '/players'],
      ['activePunishments', '/punished'],
      ['sections', '/sections'],
      ['templates', '/templates'],
      ['appealTerms', '/appeal-conditions'],
      ['clientRules', '/client-rules'],
      ['administration', '/admin'],
    ])
  })

  it('offers the active punishments to every role that may read, administrator or not', () => {
    for (const access of [STAFF, ADMIN]) {
      const items = visibleGroups(access).flatMap((group) => group.items.map((item) => item.to))
      expect(items).toContain('/punished')
    }
  })

  it('badges the inbox with its total and the work items with their own counts', () => {
    const badged = visibleGroups(ADMIN).flatMap((group) => group.items.filter((item) => item.count).map((item) => [item.key, item.count]))

    expect(badged).toEqual([
      ['inbox', 'inbox'],
      ['cases', 'cases'],
      ['appeals', 'appeals'],
      ['reports', 'reports'],
    ])
  })

  it('names the groups and destinations with the glossary terms in English', () => {
    expect(label('nav.inbox', 'en')).toBe('Inbox')
    expect(label('nav.groups.work', 'en')).toBe('Work')
    expect(label('nav.groups.players', 'en')).toBe('Players')
    expect(label('nav.groups.configuration', 'en')).toBe('Configuration')
    expect(label('nav.groups.administration', 'en')).toBe('Administration')
    expect(label('nav.playerLookup', 'en')).toBe('Player lookup')
    expect(label('nav.activePunishments', 'en')).toBe('Active punishments')
    expect(label('nav.appealTerms', 'en')).toBe('Appeal terms')
    expect(label('nav.clientRules', 'en')).toBe('Client rules')
    expect(label('nav.administration', 'en')).toBe('Administration')
  })

  it('names the groups and destinations with the glossary terms in German', () => {
    expect(label('nav.inbox', 'de')).toBe('Posteingang')
    expect(label('nav.cases', 'de')).toBe('Fälle')
    expect(label('nav.appeals', 'de')).toBe('Einsprüche')
    expect(label('nav.reports', 'de')).toBe('Meldungen')
    expect(label('nav.groups.work', 'de')).toBe('Arbeit')
    expect(label('nav.groups.players', 'de')).toBe('Spieler')
    expect(label('nav.groups.configuration', 'de')).toBe('Konfiguration')
    expect(label('nav.groups.administration', 'de')).toBe('Verwaltung')
    expect(label('nav.playerLookup', 'de')).toBe('Spielersuche')
    expect(label('nav.activePunishments', 'de')).toBe('Aktive Strafen')
    expect(label('nav.sections', 'de')).toBe('Bereiche')
    expect(label('nav.templates', 'de')).toBe('Vorlagen')
    expect(label('nav.appealTerms', 'de')).toBe('Einspruchsbedingungen')
    expect(label('nav.clientRules', 'de')).toBe('Client-Regeln')
    expect(label('nav.administration', 'de')).toBe('Verwaltung')
    i18n.global.locale.value = 'en'
  })
})
