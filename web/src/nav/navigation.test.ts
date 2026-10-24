import { describe, expect, it } from 'vitest'
import { visibleGroups } from './navigation'

describe('visibleGroups', () => {
  it('offers nothing to a user without access', () => {
    expect(visibleGroups({ hasAccess: false, administrator: false })).toEqual([])
  })

  it('offers the admin page and the system group to an administrator', () => {
    const groups = visibleGroups({ hasAccess: true, administrator: true })

    expect(groups.flatMap((group) => group.items.map((item) => item.to))).toContain('/admin')
    expect(groups.map((group) => group.key)).toContain('system')
  })

  it('hides the admin page and the then empty system group from other roles', () => {
    const groups = visibleGroups({ hasAccess: true, administrator: false })

    expect(groups.flatMap((group) => group.items.map((item) => item.to))).not.toContain('/admin')
    expect(groups.map((group) => group.key)).not.toContain('system')
  })

  it('offers the punished players to every role that may read, administrator or not', () => {
    for (const administrator of [false, true]) {
      const items = visibleGroups({ hasAccess: true, administrator }).flatMap((group) => group.items.map((item) => item.to))
      expect(items).toContain('/punished')
    }
    expect(visibleGroups({ hasAccess: false, administrator: false }).flatMap((group) => group.items.map((item) => item.to))).not.toContain('/punished')
  })

  it('groups the destinations as overview, moderation, rules and system', () => {
    const groups = visibleGroups({ hasAccess: true, administrator: true })

    expect(groups.map((group) => [group.key, group.items.map((item) => item.key)])).toEqual([
      ['overview', ['dashboard']],
      ['moderation', ['cases', 'appeals', 'reports', 'punished', 'players']],
      ['rules', ['sections', 'templates', 'appealConditions']],
      ['system', ['admin']],
    ])
  })
})
