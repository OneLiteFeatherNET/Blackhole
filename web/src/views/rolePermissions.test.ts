import { describe, expect, it, vi } from 'vitest'
import type { Appeal, EloProfile, Punishment, PunishmentProfile, Report, Section, Template } from '../api/types'
import EvidencePanel from '../components/evidence/EvidencePanel.vue'
import { clientRule } from '../test/clientRules'
import { evidenceItem, evidencePage } from '../test/evidence'
import { mockApi, mountWithApp, ok } from '../test/mount'
import AppealsView from './AppealsView.vue'
import ClientRulesView from './ClientRulesView.vue'
import PlayerDetailView from './PlayerDetailView.vue'
import ReportsView from './ReportsView.vue'
import SectionsView from './SectionsView.vue'
import TemplatesView from './TemplatesView.vue'

// The permission matrix of the admin UI: what each role is offered. The backend stays authoritative.
const NONE: string[] = []
const VIEWER = ['VIEWER']
const MODERATOR = ['MODERATOR', 'VIEWER']
const ADMIN = ['ADMIN', 'MODERATOR', 'VIEWER']

const OWNER = 'ab'.repeat(64)

const section = { key: 'network', displayName: 'Network', enforcement: 'BAN', servers: [], enabled: true, baseScore: 1000, softThreshold: null, permanentThreshold: 300, pardonScore: 700, permanentReason: 'x', decayPerDay: 1 } as unknown as Section
const template = { identifier: 'id-1', key: 'hacking', reason: 'Hacking', section: 'network', steps: [{ duration: 'P1D', eloCost: 100 }], metaData: {} } as unknown as Template
const report = { identifier: 'r-1', reporterHash: 'cd'.repeat(64), reportedHash: OWNER, category: 'CHEATING', description: 'Flying', status: 'OPEN', createdAt: 1, updatedAt: 1, metaData: {} } as unknown as Report
const appeal = { identifier: 'a-1', punishmentIdentifier: 'punishment-0001', appellantHash: OWNER, statement: 'Not me', status: 'ELIGIBLE_PENDING_REVIEW', eligibilityCheckResult: {}, createdAt: 1, updatedAt: 1, metaData: {} } as unknown as Appeal
const punishment = { identifier: 'p-1', shortId: 'ABC123', source: OWNER, verified: true, section: 'network', permanent: false, lifted: false, createdAt: 1, metaData: {} } as unknown as Punishment
const profile: PunishmentProfile = { active: { network: punishment }, history: [] }
const elo = {
  owner: OWNER,
  sections: [{ section: 'network', displayName: 'Network', enforcement: 'BAN', score: 900, baseScore: 1000, softThreshold: 700, permanentThreshold: 300, pardonScore: 700 }],
} as unknown as EloProfile

/** Labels of the items in the first row's menu, opened; empty when that row offers no menu. */
async function firstRowMenu(view: Parameters<typeof mountWithApp>[0], api: ReturnType<typeof mockApi>, roles: string[], path: string): Promise<string[]> {
  const { wrapper } = await mountWithApp(view, { api, roles, path })
  const trigger = wrapper.find('tbody tr button[aria-haspopup="menu"]')
  if (!trigger.exists()) {
    wrapper.unmount()
    return []
  }
  await trigger.trigger('click')
  const labels = wrapper.findAll('[role="menuitem"]').map((item) => item.text())
  wrapper.unmount()
  return labels
}

async function controls(view: Parameters<typeof mountWithApp>[0], api: ReturnType<typeof mockApi>, roles: string[], testIds: string[], options: { path: string; props?: Record<string, unknown> }) {
  const { wrapper } = await mountWithApp(view, { api, roles, path: options.path, props: options.props })
  const offered = testIds.filter((id) => wrapper.find(`[data-testid="${id}"]`).exists())
  wrapper.unmount()
  return offered
}

describe('role-aware controls', () => {
  describe.each([
    ['none', NONE, []],
    ['viewer', VIEWER, []],
    ['moderator', MODERATOR, ['open-apply', 'open-adjust-network', 'open-revoke-network']],
    ['admin', ADMIN, ['open-apply', 'open-adjust-network', 'open-revoke-network']],
  ])('player detail for %s', (_label, roles, expected) => {
    it('offers ban, unban and adjust only to moderators and admins', async () => {
      const api = mockApi({
        profile: vi.fn(async () => ok(profile)),
        eloProfile: vi.fn(async () => ok(elo)),
        eloHistory: vi.fn(async () => ok([])),
        evasionSightings: vi.fn(async () => ok({ detectionEnabled: true, windowDays: 7, linkedOwners: [], truncated: false })),
        intelHits: vi.fn(async () => ok([])),
        clientRuleHitsOfOwner: vi.fn(async () => ok([])),
        playerSessions: vi.fn(async () => ok({ sessions: [], page: 0, size: 50, hasMore: false })),
        listPlayerNames: vi.fn(async () => ok({ owner: OWNER, playerUuid: null, names: [], page: 0, size: 50, hasMore: false })),
      })

      const offered = await controls(PlayerDetailView, api, roles, ['open-apply', 'open-adjust-network', 'open-revoke-network'], {
        path: `/players/${OWNER}`,
        props: { owner: OWNER },
      })

      expect(offered).toEqual(expected)
    })
  })

  describe.each([
    ['none', NONE, []],
    ['viewer', VIEWER, []],
    ['moderator', MODERATOR, []],
    ['admin', ADMIN, ['new-section', 'edit-network']],
  ])('sections for %s', (_label, roles, expected) => {
    it('offers create and edit only to admins', async () => {
      const api = mockApi({ sections: vi.fn(async () => ok([section])), staffLinks: vi.fn(async () => ok([])), discordLinks: vi.fn(async () => ok([])) })

      const offered = await controls(SectionsView, api, roles, ['new-section', 'edit-network'], { path: '/sections' })

      expect(offered).toEqual(expected)
    })

    it('offers disable only in the row menu, and only to admins', async () => {
      const api = mockApi({ sections: vi.fn(async () => ok([section])), staffLinks: vi.fn(async () => ok([])), discordLinks: vi.fn(async () => ok([])) })

      const items = await firstRowMenu(SectionsView, api, roles, '/sections')

      expect(items).toEqual(roles === ADMIN ? ['Disable'] : [])
    })
  })

  // Without any role the app shows the no-access page and never the view; the view itself only gates writes.
  describe.each([
    ['none', NONE, []],
    ['viewer', VIEWER, []],
    ['moderator', MODERATOR, []],
    ['admin', ADMIN, ['new-client-rule', 'edit-meteor']],
  ])('client rules for %s', (_label, roles, expected) => {
    it('offers create and edit only to admins', async () => {
      const api = mockApi({ clientRules: vi.fn(async () => ok([clientRule()])) })

      const offered = await controls(ClientRulesView, api, roles, ['new-client-rule', 'edit-meteor'], { path: '/client-rules' })

      expect(offered).toEqual(expected)
    })

    it('reads the hits in the row menu for every team role and offers delete there only to admins', async () => {
      const api = mockApi({ clientRules: vi.fn(async () => ok([clientRule()])) })

      const items = await firstRowMenu(ClientRulesView, api, roles, '/client-rules')

      expect(items).toEqual(roles === ADMIN ? ['Show hits', 'Delete'] : ['Show hits'])
    })
  })

  describe.each([
    ['none', NONE, []],
    ['viewer', VIEWER, []],
    ['moderator', MODERATOR, []],
    ['admin', ADMIN, ['new-template', 'edit-hacking']],
  ])('templates for %s', (_label, roles, expected) => {
    it('offers create and edit only to admins', async () => {
      const api = mockApi({ templates: vi.fn(async () => ok([template])), sections: vi.fn(async () => ok([section])) })

      const offered = await controls(TemplatesView, api, roles, ['new-template', 'edit-hacking'], { path: '/templates' })

      expect(offered).toEqual(expected)
    })

    it('offers delete only in the row menu, and only to admins', async () => {
      const api = mockApi({ templates: vi.fn(async () => ok([template])), sections: vi.fn(async () => ok([section])) })

      const items = await firstRowMenu(TemplatesView, api, roles, '/templates')

      expect(items).toEqual(roles === ADMIN ? ['Delete'] : [])
    })
  })

  describe.each([
    ['none', NONE, []],
    ['viewer', VIEWER, []],
    ['moderator', MODERATOR, ['resolve-r-1']],
    ['admin', ADMIN, ['resolve-r-1']],
  ])('reports for %s', (_label, roles, expected) => {
    it('offers to resolve a report only to moderators and admins', async () => {
      const api = mockApi({ reports: vi.fn(async () => ok([report])), templates: vi.fn(async () => ok([template])) })

      // Resolving is in the row menu, which only moderators and admins get; its trigger is the control here.
      const { wrapper } = await mountWithApp(ReportsView, { api, roles, path: '/reports' })
      const offered = wrapper.find('[aria-haspopup="menu"]').exists() ? ['resolve-r-1'] : []
      wrapper.unmount()

      expect(offered).toEqual(expected)
    })
  })

  describe.each([
    ['none', NONE, []],
    ['viewer', VIEWER, []],
    ['moderator', MODERATOR, ['decide-a-1']],
    ['admin', ADMIN, ['decide-a-1']],
  ])('appeals for %s', (_label, roles, expected) => {
    it('offers to decide an appeal only to moderators and admins', async () => {
      const api = mockApi({ appeals: vi.fn(async () => ok([appeal])), caseTimeline: vi.fn(async () => ok({ items: [], nextCursor: null })) })

      const offered = await controls(AppealsView, api, roles, ['decide-a-1'], { path: '/appeals' })

      expect(offered).toEqual(expected)
    })
  })

  describe.each([
    ['none', NONE, []],
    ['viewer', VIEWER, []],
    ['moderator', MODERATOR, ['open-add-link']],
    ['admin', ADMIN, ['open-add-link', 'delete']],
  ])('evidence for %s', (_label, roles, expected) => {
    it('offers adding to moderators and deleting to admins only', async () => {
      const api = mockApi({
        evidence: vi.fn(async () => ok(evidencePage([evidenceItem({ kind: 'LINK', reference: 'https://example.org/x' })]))),
        evidenceCounts: vi.fn(async () => ok({ count: 1, playerSupplied: 0 })),
      })

      const offered = await controls(EvidencePanel, api, roles, ['open-add-link', 'delete'], { path: '/', props: { punishment: 'p-1' } })

      expect(offered).toEqual(expected)
    })
  })

  it('offers the evidence upload zone to a moderator and not to a viewer', async () => {
    const api = mockApi({ evidence: vi.fn(async () => ok(evidencePage([]))), evidenceCounts: vi.fn(async () => ok({ count: 0, playerSupplied: 0 })) })
    const moderator = await mountWithApp(EvidencePanel, { api, roles: MODERATOR, props: { punishment: 'p-1' } })
    const viewer = await mountWithApp(EvidencePanel, { api, roles: VIEWER, props: { punishment: 'p-1' } })

    expect(moderator.wrapper.find('[data-testid="evidence-panel"] [aria-label="Upload evidence files"]').exists(), 'moderator zone').toBe(true)
    expect(viewer.wrapper.find('[data-testid="evidence-panel"] [aria-label="Upload evidence files"]').exists(), 'viewer zone').toBe(false)

    moderator.wrapper.unmount()
    viewer.wrapper.unmount()
  })
})
