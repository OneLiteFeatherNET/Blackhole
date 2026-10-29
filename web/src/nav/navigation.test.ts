import { describe, expect, it } from 'vitest'
import { i18n } from '../i18n'
import { visibleGroups, type WorkspaceKey } from './navigation'

const ADMIN = { hasAccess: true, administrator: true }
const MODERATOR = { hasAccess: true, administrator: false }
const VIEWER = { hasAccess: true, administrator: false }
const NONE = { hasAccess: false, administrator: false }

/** Group key, whether it has a heading, and the destination keys, for what the access offers in a workspace. */
function shape(access: { hasAccess: boolean; administrator: boolean }, workspace: WorkspaceKey) {
  return visibleGroups(access, workspace).map((group) => [group.key, group.heading, group.items.map((item) => item.key)])
}

/** A translated label in the given locale; the test that changes the locale restores English. */
function label(key: string, locale: 'en' | 'de'): string {
  i18n.global.locale.value = locale
  return i18n.global.t(key)
}

describe('visibleGroups for the moderation workspace', () => {
  it('offers nothing to a user without access', () => {
    expect(visibleGroups(NONE, 'moderation')).toEqual([])
  })

  it.each([['administrator', ADMIN], ['moderator', MODERATOR]])('lists the inbox, then Work and Players for an %s', (_role, access) => {
    expect(shape(access, 'moderation')).toEqual([
      ['home', false, ['inbox']],
      ['work', true, ['cases', 'appeals', 'reports']],
      ['players', true, ['playerLookup', 'activePunishments']],
    ])
  })

  it('offers no administration destination in the moderation workspace', () => {
    const items = visibleGroups(ADMIN, 'moderation').flatMap((group) => group.items.map((item) => item.to))

    expect(items.some((to) => to.startsWith('/admin'))).toBe(false)
  })

  it('routes every moderation destination to its existing URL', () => {
    const routes = visibleGroups(MODERATOR, 'moderation').flatMap((group) => group.items.map((item) => [item.key, item.to]))

    expect(routes).toEqual([
      ['inbox', '/'],
      ['cases', '/cases'],
      ['appeals', '/appeals'],
      ['reports', '/reports'],
      ['playerLookup', '/players'],
      ['activePunishments', '/punished'],
    ])
  })

  it('badges the inbox with its total and the work items with their own counts', () => {
    const badged = visibleGroups(ADMIN, 'moderation').flatMap((group) => group.items.filter((item) => item.count).map((item) => [item.key, item.count]))

    expect(badged).toEqual([
      ['inbox', 'inbox'],
      ['cases', 'cases'],
      ['appeals', 'appeals'],
      ['reports', 'reports'],
    ])
  })
})

describe('visibleGroups for the administration workspace', () => {
  it('lists Configuration and System for an administrator, with no system status item', () => {
    expect(shape(ADMIN, 'administration')).toEqual([
      ['configuration', true, ['templates', 'sections', 'clientRules', 'appealTerms']],
      ['system', true, ['staffAccounts', 'imports', 'cloudnet', 'kick', 'audit']],
    ])
  })

  it.each([['moderator', MODERATOR], ['viewer', VIEWER], ['no role', NONE]])('offers nothing to a %s', (_role, access) => {
    expect(visibleGroups(access, 'administration')).toEqual([])
  })

  it('routes the administration destinations to their /admin URLs', () => {
    const routes = visibleGroups(ADMIN, 'administration').flatMap((group) => group.items.map((item) => [item.key, item.to]))

    expect(routes).toEqual([
      ['templates', '/admin/templates'],
      ['sections', '/admin/sections'],
      ['clientRules', '/admin/client-rules'],
      ['appealTerms', '/admin/appeal-terms'],
      ['staffAccounts', '/admin/staff'],
      ['imports', '/admin/imports'],
      ['cloudnet', '/admin/cloudnet'],
      ['kick', '/admin/kick'],
      ['audit', '/admin/audit'],
    ])
  })
})

describe('navigation labels', () => {
  it('names the moderation destinations with the glossary terms in English', () => {
    expect(label('nav.inbox', 'en')).toBe('Inbox')
    expect(label('nav.groups.work', 'en')).toBe('Work')
    expect(label('nav.groups.players', 'en')).toBe('Players')
    expect(label('nav.playerLookup', 'en')).toBe('Player lookup')
    expect(label('nav.activePunishments', 'en')).toBe('Active punishments')
  })

  it('names the administration destinations and groups in English', () => {
    expect(label('nav.groups.configuration', 'en')).toBe('Configuration')
    expect(label('nav.groups.system', 'en')).toBe('System')
    expect(label('nav.staffAccounts', 'en')).toBe('Staff accounts')
    expect(label('nav.appealTerms', 'en')).toBe('Appeal terms')
    expect(label('nav.clientRules', 'en')).toBe('Client rules')
    expect(label('nav.imports', 'en')).toBe('Imports')
    expect(label('nav.cloudnet', 'en')).toBe('CloudNet')
    expect(label('nav.audit', 'en')).toBe('Audit log')
  })

  it('names the destinations with the glossary terms in German', () => {
    expect(label('nav.inbox', 'de')).toBe('Posteingang')
    expect(label('nav.cases', 'de')).toBe('Fälle')
    expect(label('nav.appeals', 'de')).toBe('Einsprüche')
    expect(label('nav.reports', 'de')).toBe('Meldungen')
    expect(label('nav.groups.work', 'de')).toBe('Arbeit')
    expect(label('nav.groups.players', 'de')).toBe('Spieler')
    expect(label('nav.playerLookup', 'de')).toBe('Spielersuche')
    expect(label('nav.activePunishments', 'de')).toBe('Aktive Strafen')
    expect(label('nav.sections', 'de')).toBe('Bereiche')
    expect(label('nav.templates', 'de')).toBe('Vorlagen')
    expect(label('nav.appealTerms', 'de')).toBe('Einspruchsbedingungen')
    expect(label('nav.clientRules', 'de')).toBe('Client-Regeln')
    expect(label('nav.groups.configuration', 'de')).toBe('Konfiguration')
    expect(label('nav.groups.system', 'de')).toBe('System')
    expect(label('nav.imports', 'de')).toBe('Importe')
    i18n.global.locale.value = 'en'
  })
})
