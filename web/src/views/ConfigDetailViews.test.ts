import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { AppealConditions, ClientRule, Section, Template } from '../api/types'
import { fail, mockApi, mountRouted, ok } from '../test/mount'

const template = {
  identifier: 'id-hacking',
  key: 'hacking',
  reason: 'Hacking',
  section: 'network',
  escalationGroup: 'cheating',
  escalationWindow: 'P90D',
  steps: [{ duration: 'P1D', eloCost: 100 }],
  metaData: {},
} as unknown as Template

const section = {
  key: 'network',
  displayName: 'Network',
  enforcement: 'BAN',
  targetKind: 'PATTERN',
  servers: [],
  baseScore: 1000,
  pardonScore: 700,
  decayPerDay: 10,
  softThreshold: 700,
  softDuration: 'P3D',
  permanentThreshold: 250,
  enabled: true,
} as unknown as Section

const rule = {
  key: 'meteor',
  displayName: 'Meteor client',
  kind: 'CHANNEL',
  enabled: true,
  patterns: ['meteor-client:*'],
  negate: false,
  scope: 'GLOBAL',
  sectionKeys: [],
  servers: [],
  action: 'NOTIFY',
  acknowledgedSpoofable: false,
  hideCommand: false,
  hitCount: 0,
} as unknown as ClientRule

const terms = { version: 3, text: 'Terms three', createdAt: '2030-01-03T10:00:00Z' } as AppealConditions

/** The fake API of the detail pages: every method exists, each answers with its item unless a test says otherwise. */
function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    template: vi.fn(async () => ok(template)),
    deleteTemplate: vi.fn(async () => ok(template)),
    updateTemplate: vi.fn(async () => ok(template)),
    sections: vi.fn(async () => ok([section])),
    section: vi.fn(async () => ok(section)),
    disableSection: vi.fn(async () => ok({ ...section, enabled: false })),
    clientRule: vi.fn(async () => ok(rule)),
    clientRules: vi.fn(async () => ok([rule])),
    clientRuleHits: vi.fn(async () => ok([])),
    appealConditionVersion: vi.fn(async () => ok(terms)),
    appealConditions: vi.fn(async () => ok(terms)),
    appealConditionVersions: vi.fn(async () => ok([terms])),
    ...overrides,
  }
  return { api, mount: (path: string, roles?: string[]) => mountRouted({ api: mockApi(api), path, roles }) }
}

describe('template address', () => {
  it('shows the template at its own address with a copy-link button', async () => {
    const { mount } = setup()
    const { wrapper } = await mount('/admin/templates/id-hacking')

    expect(wrapper.text()).toContain('Hacking')
    expect(wrapper.find('[data-testid="copy-link"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows the not-found text and no data for an unknown identifier', async () => {
    const { mount } = setup({ template: vi.fn(async () => fail('no template', 404, 'NOT_FOUND')) })
    const { wrapper } = await mount('/admin/templates/id-missing')

    expect(wrapper.get('[data-testid="not-found"]').text()).toContain('does not exist')
    expect(wrapper.find('dl').exists(), 'no facts of a missing template').toBe(false)

    wrapper.unmount()
  })

  it('offers the edit action to an administrator only', async () => {
    const { mount } = setup()
    const moderator = await mount('/admin/templates/id-hacking', ['MODERATOR', 'VIEWER'])
    expect(moderator.wrapper.find('[data-testid="edit-template"]').exists()).toBe(false)
    moderator.wrapper.unmount()

    const admin = await mount('/admin/templates/id-hacking', ['ADMIN', 'MODERATOR', 'VIEWER'])
    expect(admin.wrapper.find('[data-testid="edit-template"]').exists()).toBe(true)
    admin.wrapper.unmount()
  })

  it('opens the edit dialog on its own address and sends nothing on open', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount('/admin/templates/id-hacking/edit')

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(api.updateTemplate).not.toHaveBeenCalled()
    expect(api.deleteTemplate).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('section address', () => {
  it('opens the disable confirmation on navigation without sending anything', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount('/admin/sections/network/disable')

    expect(wrapper.get('[role="dialog"]').text()).toContain('Disable section network?')
    expect(api.disableSection, 'no action on navigation').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('sends the disable once, when the confirmation is pressed', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount('/admin/sections/network/disable')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(api.disableSection).toHaveBeenCalledTimes(1)
    expect(api.disableSection).toHaveBeenCalledWith('network')

    wrapper.unmount()
  })

  it('shows the not-found text for an unknown key and opens no dialog for it', async () => {
    const { api, mount } = setup({ section: vi.fn(async () => fail('no section', 404, 'NOT_FOUND')) })
    const { wrapper } = await mount('/admin/sections/nope/disable')

    expect(wrapper.find('[data-testid="not-found"]').exists()).toBe(true)
    expect(wrapper.find('[role="dialog"]').exists(), 'no empty dialog').toBe(false)
    expect(api.disableSection).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('client rule address', () => {
  it('reads the hits only once the hits tab is open', async () => {
    const { api, mount } = setup()
    const details = await mount('/admin/client-rules/meteor')
    expect(api.clientRuleHits, 'the details tab reads no hits').not.toHaveBeenCalled()
    details.wrapper.unmount()

    const hits = await mount('/admin/client-rules/meteor?tab=hits')
    expect(api.clientRuleHits).toHaveBeenCalledWith('meteor')
    expect(hits.wrapper.find('[data-testid="hits-empty"]').exists()).toBe(true)
    hits.wrapper.unmount()
  })

  it('shows the not-found text for an unknown key', async () => {
    const { mount } = setup({ clientRule: vi.fn(async () => fail('no rule', 404, 'NOT_FOUND')) })
    const { wrapper } = await mount('/admin/client-rules/nope')

    expect(wrapper.find('[data-testid="not-found"]').exists()).toBe(true)

    wrapper.unmount()
  })
})

describe('appeal terms version address', () => {
  it('reads the version by its number and shows its text', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount('/admin/appeal-terms/versions/3')

    expect(api.appealConditionVersion).toHaveBeenCalledWith(3)
    expect(wrapper.get('[data-testid="version-text"]').text()).toContain('Terms three')
    expect(wrapper.find('[data-testid="copy-link"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows the not-found text for a version that does not exist', async () => {
    const { mount } = setup({ appealConditionVersion: vi.fn(async () => fail('no version', 404, 'NOT_FOUND')) })
    const { wrapper } = await mount('/admin/appeal-terms/versions/99')

    expect(wrapper.find('[data-testid="not-found"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="version-text"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
