import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { Punishment, Report, Section, Template } from '../api/types'
import { evidenceItem } from '../test/evidence'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import { chooseOption } from '../test/select'
import ReportDetailView from './ReportDetailView.vue'

const ID = '3f2b8c1e-0000-4000-8000-000000000001'
const REPORTED = 'cd'.repeat(64)
const REPORTER = 'ef'.repeat(64)
const EVIDENCE = '9d2a0000-0000-4000-8000-000000000009'

function report(overrides: Partial<Report> = {}): Report {
  return {
    identifier: ID,
    reporterHash: REPORTER,
    reportedHash: REPORTED,
    category: 'CHEATING',
    description: 'Flying around the spawn',
    status: 'OPEN',
    evidenceReferences: [EVIDENCE],
    createdAt: Date.UTC(2030, 0, 2, 10, 0),
    updatedAt: Date.UTC(2030, 0, 2, 10, 0),
    verified: false,
    metaData: {},
    ...overrides,
  } as Report
}

const chatLog = evidenceItem({
  identifier: EVIDENCE,
  kind: 'CHAT_LOG',
  status: 'VALID',
  payload: { lines: [{ timestamp: 1, speaker: 'Spieler A', text: 'you are all bad' }] },
})

const templates = [
  { identifier: 'tpl-1', key: 'hacking', reason: 'Hacking', section: 'network', steps: [] },
  { identifier: 'tpl-2', key: 'spam', reason: 'Spam', section: 'chat', steps: [] },
] as unknown as Template[]
const sections = [
  { key: 'network', enforcement: 'BAN' },
  { key: 'chat', enforcement: 'MUTE' },
] as unknown as Section[]
const active = { network: { identifier: 'p-1', section: 'network', permanent: false, createdAt: 1_700_000_000_000 } as unknown as Punishment }

function setup(options: { roles?: string[]; report?: Report; path?: string; extra?: Record<string, unknown> } = {}) {
  const api = {
    report: vi.fn(async () => ok(options.report ?? report())),
    reportsAgainst: vi.fn(async () => ok([report(), report({ identifier: 'other-1', category: 'GRIEFING', status: 'DISMISSED' })])),
    profile: vi.fn(async () => ok({ active, history: [] })),
    evidenceItem: vi.fn(async () => ok(chatLog)),
    templates: vi.fn(async () => ok(templates)),
    sections: vi.fn(async () => ok(sections)),
    previewApply: vi.fn(async () =>
      ok({ section: 'network', enforcement: 'BAN', step: 1, stepCount: 2, duration: 'P7D', eloCost: 100, scoreBefore: 1000, scoreAfter: 900, permanentThreshold: 0, escalatesToPermanent: false }),
    ),
    applyTemplate: vi.fn(),
    resolveReport: vi.fn(async () => ok(report({ status: 'ACTIONED' }))),
    ...options.extra,
  }
  return { api, mount: () => mountWithApp(ReportDetailView, { api: mockApi(api), path: options.path ?? `/reports/${ID}`, props: { id: ID }, roles: options.roles }) }
}

/** Dismiss is in the overflow menu of the header (the other action is the primary one). */
async function chooseDismiss(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('[data-testid="entity-header"] button[aria-haspopup="menu"]').trigger('click')
  const item = wrapper.findAll('[role="menuitem"]').find((candidate) => candidate.text() === 'Dismiss')
  if (!item) throw new Error('no Dismiss item in the overflow menu')
  await item.trigger('click')
}

describe('ReportDetailView: page template', () => {
  it('shows the report title as the only h1, with no second heading of the same text', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.findAll('h1').map((heading) => heading.text())).toEqual(['Report'])
    expect(wrapper.findAll('h2').map((heading) => heading.text())).not.toContain('Report')

    wrapper.unmount()
  })

  it('leads back to the reports through breadcrumbs, not a text link', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const crumbs = wrapper.findAll('nav[aria-label="Breadcrumbs"] a')
    // The default test user is an administrator, so the workspace leads the trail; the list is the link before the report.
    expect(crumbs.map((crumb) => crumb.text())).toEqual(['Moderation', 'Reports'])
    expect(crumbs[1]!.attributes('href')).toBe('/reports')
    expect(wrapper.get('nav[aria-label="Breadcrumbs"] [aria-current="page"]').text()).toBe(`#${ID.slice(0, 8)}`)
    expect(wrapper.findAll('a').map((link) => link.text())).not.toContain('Back to reports')

    wrapper.unmount()
  })

  it('puts each section under a second-level heading', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('section[aria-labelledby="report-summary"] > h2').text()).toBe('Summary')
    expect(wrapper.get('section[aria-labelledby="report-others"] > h2').text()).toBe('Other reports against this player')

    wrapper.unmount()
  })

  it('describes the reporter hash by its pseudonymous note instead of a tooltip', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const summary = wrapper.get('[data-testid="summary"]')
    expect(summary.find('[title]').exists(), 'no title attribute').toBe(false)
    const hash = summary.findAll('[aria-describedby]').find((node) => node.text().startsWith(REPORTER.slice(0, 8)))
    expect(hash, 'the reporter hash carries aria-describedby').toBeDefined()
    const described = wrapper.get(`[id="${hash!.attributes('aria-describedby')}"]`)
    expect(described.text()).toBe('(pseudonymous)')

    wrapper.unmount()
  })
})

describe('ReportDetailView: content', () => {
  it('shows the report without any raw identifier of a person', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(api.report).toHaveBeenCalledWith(ID)
    const summary = wrapper.get('[data-testid="summary"]').text()
    expect(summary).toContain('Cheating')
    expect(summary).toContain('Flying around the spawn')
    expect(summary).toContain(`${REPORTER.slice(0, 8)}…`)
    expect(summary).toContain('(pseudonymous)')
    expect(wrapper.text()).not.toContain(REPORTER)
    expect(wrapper.get(`a[href="/players/${REPORTED}"]`).text()).toBe(`${REPORTED.slice(0, 8)}…`)
    wrapper.unmount()
  })

  it('shows the attached chat excerpt with the existing evidence components', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(api.evidenceItem).toHaveBeenCalledWith(EVIDENCE)
    await wrapper.get('[data-testid="expand"]').trigger('click')
    expect(wrapper.get('[data-testid="detail"]').text()).toContain('you are all bad')
    wrapper.unmount()
  })

  it('lists the other reports against the player but not this one, with links', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(api.reportsAgainst).toHaveBeenCalledWith(REPORTED)
    const links = wrapper.get('[data-testid="others"]').findAll('a')
    expect(links).toHaveLength(1)
    expect(links[0]!.attributes('href')).toBe('/reports/other-1')
    expect(links[0]!.text()).toContain('Griefing')
    wrapper.unmount()
  })

  it('shows the active punishments of the reported player with their case link', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="active-network"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="open-case-network"]').attributes('href')).toBe('/cases/p-1')
    wrapper.unmount()
  })

  it('says so when there are no other reports', async () => {
    const { mount } = setup({ extra: { reportsAgainst: vi.fn(async () => ok([report()])) } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="others-empty"]').text()).toContain('no other reports')
    wrapper.unmount()
  })

  it('keeps the report on screen when a side section fails and says which one', async () => {
    const { mount } = setup({ extra: { profile: vi.fn(async () => fail('boom', 500, 'UNKNOWN')) } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="summary"]').text()).toContain('Cheating')
    expect(wrapper.text()).toContain('This section could not be loaded.')
    wrapper.unmount()
  })

  it('counts evidence that can no longer be loaded', async () => {
    const { mount } = setup({ extra: { evidenceItem: vi.fn(async () => fail('gone', 404, 'NOT_FOUND')) } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="evidence-missing"]').text()).toContain('1 evidence item could not be loaded')
    wrapper.unmount()
  })

  it('shows an error with a retry when the report does not exist', async () => {
    const { mount } = setup({ extra: { report: vi.fn(async () => fail('there is no report with this identifier', 404, 'NOT_FOUND')) } })
    const { wrapper } = await mount()

    expect(wrapper.get('[role="alert"]').text()).toContain('there is no report with this identifier')
    expect(wrapper.find('[data-testid="summary"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('ReportDetailView: actions', () => {
  it('offers no actions to a viewer', async () => {
    const { mount } = setup({ roles: ['VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="apply-template"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="dismiss"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('offers no actions for a report that is already resolved and shows its note', async () => {
    const { mount } = setup({ report: report({ status: 'DISMISSED', resolutionNote: 'not enough proof' }) })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="dismiss"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="resolution-note"]').text()).toBe('not enough proof')
    wrapper.unmount()
  })

  it('shows the apply action of a resolved report disabled, with the reason as text', async () => {
    const { mount } = setup({ report: report({ status: 'DISMISSED' }) })
    const { wrapper } = await mount()

    const apply = wrapper.get('[data-testid="apply-template"]')
    expect(apply.attributes('disabled')).toBeDefined()
    expect(wrapper.get(`[id="${apply.attributes('aria-describedby')}"]`).text()).toBe('This report is already resolved.')
    wrapper.unmount()
  })

  it('links the header owner to the reported player by the owner hash', async () => {
    const { mount } = setup()
    const { wrapper, router } = await mount()

    expect(wrapper.get('[data-testid="report-owner"]').attributes('href')).toBe(router.resolve(`/players/${REPORTED}`).href)
    wrapper.unmount()
  })

  it('dismisses with a note through the resolve route and reloads the report', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await chooseDismiss(wrapper)
    await flushPromises()
    const confirm = wrapper.get('[role="dialog"] [data-testid="confirm"]')
    expect(confirm.attributes('disabled'), 'a note is required').toBeDefined()

    await wrapper.get('[role="dialog"] input').setValue('  no proof in the chat  ')
    await confirm.trigger('click')
    await flushPromises()

    expect(api.resolveReport).toHaveBeenCalledWith(ID, { status: 'DISMISSED', note: 'no proof in the chat' })
    expect(api.report).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('Report dismissed.')
    wrapper.unmount()
  })

  it('shows the error of a failed dismissal and keeps the dialog open', async () => {
    const { mount } = setup({ extra: { resolveReport: vi.fn(async () => fail('the report is already resolved (DISMISSED)', 409, 'CONFLICT')) } })
    const { wrapper } = await mount()

    await chooseDismiss(wrapper)
    await flushPromises()
    await wrapper.get('[role="dialog"] input').setValue('note')
    await wrapper.get('[role="dialog"] [data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="dialog"] [data-testid="error"]').text()).toContain('already resolved')
    wrapper.unmount()
  })

  it('applies a template by resolving the report as actioned, not by applying it separately', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="apply-template"]').trigger('click')
    await flushPromises()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'hacking')
    await flushPromises()
    await wrapper.get('[role="dialog"] [data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.previewApply).toHaveBeenCalledWith(REPORTED, 'hacking')
    expect(api.resolveReport).toHaveBeenCalledWith(ID, { status: 'ACTIONED', templateId: 'tpl-1' })
    expect(api.applyTemplate, 'the resolve route applies the template, a second apply would punish twice').not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('Report actioned: punishment applied in network.')
    wrapper.unmount()
  })
})

describe('ReportDetailView: the action routes', () => {
  it('opens the resolve dialog from its address, loads the templates and sends nothing', async () => {
    const { api, mount } = setup({ path: `/reports/${ID}/resolve` })
    const { wrapper } = await mount()

    expect(api.templates).toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(api.resolveReport, 'navigation changes nothing (decision 8, rule 4)').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('sends the actioned resolution with the template chosen on the resolve route, then returns to the report', async () => {
    const { api, mount } = setup({ path: `/reports/${ID}/resolve` })
    const { wrapper, router } = await mount()

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'hacking')
    await flushPromises()
    await wrapper.get('[role="dialog"] [data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.resolveReport).toHaveBeenCalledTimes(1)
    expect(api.resolveReport).toHaveBeenCalledWith(ID, { status: 'ACTIONED', templateId: 'tpl-1' })
    expect(router.currentRoute.value.fullPath).toBe(`/reports/${ID}`)
    wrapper.unmount()
  })

  it('opens the resolve route from the primary action of the report page without sending anything', async () => {
    const { api, mount } = setup()
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="apply-template"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('report-resolve')
    expect(api.resolveReport).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('opens the dismiss dialog from its address and sends nothing until it is confirmed', async () => {
    const { api, mount } = setup({ path: `/reports/${ID}/dismiss` })
    const { wrapper } = await mount()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(api.resolveReport).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('sends the dismissal with its note when confirmed on the dismiss route, then returns to the report', async () => {
    const { api, mount } = setup({ path: `/reports/${ID}/dismiss` })
    const { wrapper, router } = await mount()

    await wrapper.get('[role="dialog"] input').setValue('no proof in the chat')
    await wrapper.get('[role="dialog"] [data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.resolveReport).toHaveBeenCalledWith(ID, { status: 'DISMISSED', note: 'no proof in the chat' })
    expect(router.currentRoute.value.fullPath).toBe(`/reports/${ID}`)
    wrapper.unmount()
  })

  it('closes the dismiss dialog with Back without sending anything', async () => {
    const { api, mount } = setup()
    const { wrapper, router } = await mount()
    await router.push(`/reports/${ID}/dismiss`)
    await flushPromises()
    expect(wrapper.get('[role="dialog"]').isVisible()).toBe(true)

    router.back()
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe(`/reports/${ID}`)
    expect(wrapper.find('[role="dialog"]').exists() && wrapper.get('[role="dialog"]').isVisible()).toBe(false)
    expect(api.resolveReport).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('closes the resolve dialog with Back without sending anything', async () => {
    const { api, mount } = setup()
    const { wrapper, router } = await mount()
    await router.push(`/reports/${ID}/resolve`)
    await flushPromises()
    expect(wrapper.get('[role="dialog"]').isVisible()).toBe(true)

    router.back()
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe(`/reports/${ID}`)
    expect(api.resolveReport).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('opens no action dialog for a viewer, whatever the address says', async () => {
    const { api, mount } = setup({ roles: ['VIEWER'], path: `/reports/${ID}/resolve` })
    const { wrapper } = await mount()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(api.templates).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('opens no action dialog for a resolved report, whatever the address says', async () => {
    const { api, mount } = setup({ report: report({ status: 'DISMISSED' }), path: `/reports/${ID}/dismiss` })
    const { wrapper } = await mount()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(api.resolveReport).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('opens no empty dialog for a report that does not exist', async () => {
    const reportMissing = vi.fn(async () => fail('there is no report with this identifier', 404, 'NOT_FOUND'))
    const { api, mount } = setup({ extra: { report: reportMissing }, path: `/reports/${ID}/dismiss` })
    const { wrapper } = await mount()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(api.resolveReport).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
