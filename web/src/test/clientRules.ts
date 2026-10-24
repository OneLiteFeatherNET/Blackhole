import type { VueWrapper } from '@vue/test-utils'
import type { ClientRule, ClientRuleCatalogue, ClientRuleHit, Section, Template } from '../api/types'

/** A rule with every field filled; tests override what they care about. */
export function clientRule(overrides: Partial<ClientRule> = {}): ClientRule {
  return {
    key: 'meteor',
    displayName: 'Meteor client',
    kind: 'CHANNEL',
    enabled: true,
    patterns: ['meteor-client:*'],
    negate: false,
    scope: 'GLOBAL',
    sectionKeys: [],
    targetKind: 'PATTERN',
    servers: [],
    action: 'NOTIFY',
    acknowledgedSpoofable: false,
    hideCommand: false,
    hitCount: 0,
    createdAt: '2026-10-24T10:00:00Z',
    updatedAt: '2026-10-24T10:00:00Z',
    ...overrides,
  }
}

export function catalogue(overrides: Partial<ClientRuleCatalogue> = {}): ClientRuleCatalogue {
  return {
    available: true,
    reportedAt: '2026-10-24T10:00:00Z',
    brands: [{ name: 'lunarclient', players: 4 }],
    channels: [{ name: 'meteor-client:main', players: 3 }],
    commands: ['op', 'minecraft:op'],
    protocols: [{ version: 767, name: '1.21-1.21.1' }],
    proxies: ['Proxy-1'],
    ...overrides,
  }
}

export function hit(overrides: Partial<ClientRuleHit> = {}): ClientRuleHit {
  return { rule: 'meteor', owner: 'ab'.repeat(64), at: '2026-10-24T10:00:00Z', action: 'NOTIFY', enforced: false, bypassed: false, server: 'lobby-1', ...overrides }
}

export const serverSection = {
  key: 'minigames',
  displayName: 'Minigames',
  enforcement: 'SERVER',
  targetKind: 'PATTERN',
  servers: ['mini-*'],
  baseScore: 1000,
  softThreshold: 700,
  softDuration: 'P3D',
  permanentThreshold: 300,
  pardonScore: 700,
  permanentReason: 'x',
  decayPerDay: 10,
  enabled: true,
} as Section

export const cheatTemplate = { identifier: 'id-1', key: 'old-client', reason: 'Old client', section: 'network', steps: [{ duration: 'P1D', eloCost: 100 }], metaData: {} } as unknown as Template

type Wrapper = VueWrapper

/** The input of a dialog field by its label. */
export function field(wrapper: Wrapper, label: string) {
  const found = wrapper.findAll('[role="dialog"] label').find((l) => l.text().startsWith(label))
  if (!found) throw new Error(`no field labelled ${label}`)
  return wrapper.get(`[role="dialog"] [id="${found.attributes('for')}"]`)
}

/** The combobox of a dialog select by its label. */
export function combo(wrapper: Wrapper, label: string) {
  const found = wrapper.findAll('[role="dialog"] [role="combobox"]').find((c) => {
    const labelId = c.attributes('aria-labelledby')?.split(' ')[0]
    return (labelId ? document.getElementById(labelId)?.textContent?.trim() : '')?.startsWith(label)
  })
  if (!found) throw new Error(`no select labelled ${label}; there are ${wrapper.findAll('[role="dialog"] [role="combobox"]').map((c) => document.getElementById(c.attributes('aria-labelledby')?.split(' ')[0] ?? '')?.textContent).join(' / ')}; dialogs ${wrapper.findAll('[role="dialog"]').length}`)
  return found
}
