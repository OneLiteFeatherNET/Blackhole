import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { cheatTemplate, catalogue, clientRule, combo, field, hit, serverSection } from '../test/clientRules'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import { chooseOption as choose } from '../test/select'
import ClientRulesView from './ClientRulesView.vue'

const watching = clientRule({ key: 'meteor', hitCount: 12 })
const denying = clientRule({ key: 'no-op', displayName: 'No op', kind: 'COMMAND', patterns: ['op'], action: 'DENY', hideCommand: true, scope: 'SERVERS', sectionKeys: ['minigames'] })
const protocol = clientRule({ key: 'old', displayName: 'Old versions', kind: 'PROTOCOL', patterns: [], protocolMin: 47, protocolMax: 340, negate: true, action: 'TEMPLATE', templateKey: 'old-client' })
const spoofable = clientRule({ key: 'punish-brand', kind: 'BRAND', patterns: ['cheat*'], action: 'TEMPLATE', templateKey: 'old-client', acknowledgedSpoofable: true })

function setup(overrides: Record<string, unknown> = {}, roles?: string[]) {
  const api = {
    clientRules: vi.fn(async () => ok([watching, denying, protocol, spoofable])),
    createClientRule: vi.fn(async () => ok(watching)),
    updateClientRule: vi.fn(async () => ok(watching)),
    deleteClientRule: vi.fn(async () => ok(watching)),
    clientRuleHits: vi.fn(async () => ok([hit(), hit({ owner: 'cd'.repeat(64), action: 'DENY', enforced: true }), hit({ bypassed: true })])),
    clientRuleCatalogue: vi.fn(async () => ok(catalogue())),
    sections: vi.fn(async () => ok([serverSection])),
    templates: vi.fn(async () => ok([cheatTemplate])),
    cloudnetCatalogue: vi.fn(async () => ok({ available: false, tasks: [], groups: [], services: [], proxies: [] })),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(ClientRulesView, { api: mockApi(api), path: '/client-rules', roles }) }
}

/** Picks an option and lets the dialog re-render before the test looks at it. */
async function chooseOption(combobox: Parameters<typeof choose>[0], value: string): Promise<void> {
  await choose(combobox, value)
  await flushPromises()
}

describe('ClientRulesView: list', () => {
  it('lists the rules with their match, scope, action and hit counts', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(4)
    expect(rows[0]!.text()).toContain('meteor-client:*')
    expect(rows[0]!.text()).toContain('Plugin channel')
    expect(rows[0]!.text()).toContain('Hits: 12')
    expect(rows[1]!.text()).toContain('minigames')
    expect(rows[1]!.text()).toContain('Hidden from tab completion')
    expect(rows[1]!.text()).toContain('Deny')
    expect(rows[2]!.text()).toContain('47 - 340')
    expect(rows[2]!.text()).toContain('everything except')
    expect(rows[2]!.text()).toContain('old-client')

    wrapper.unmount()
  })

  it('says it plainly: brand, mods and channels can be faked, a hit is not proof', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="honesty"]').text()).toContain('can be faked')
    expect(wrapper.get('[data-testid="honesty"]').text()).toContain('not proof')

    wrapper.unmount()
  })

  it('marks a rule that punishes on a value the client can fake as acknowledged', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.findAll('tbody tr')[3]!.get('[data-testid="acknowledged"]').text()).toContain('acknowledged')
    expect(wrapper.findAll('tbody tr')[0]!.find('[data-testid="acknowledged"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('marks a disabled rule and shows an empty state', async () => {
    const off = clientRule({ key: 'off', enabled: false })
    const { mount } = setup({ clientRules: vi.fn(async () => ok([off])) })
    const { wrapper } = await mount()
    expect(wrapper.get('tbody').text()).toContain('Disabled')
    wrapper.unmount()

    const empty = await setup({ clientRules: vi.fn(async () => ok([])) }).mount()
    expect(empty.wrapper.get('[data-testid="empty"]').text()).toContain('no client rules')
    empty.wrapper.unmount()
  })

  it('shows the error with a retry when loading fails', async () => {
    const clientRules = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok([watching]))
    const { mount } = setup({ clientRules })
    const { wrapper } = await mount()

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('tbody tr')).toHaveLength(1)

    wrapper.unmount()
  })
})

describe('ClientRulesView: roles', () => {
  it.each([
    ['viewer', ['VIEWER']],
    ['moderator', ['MODERATOR', 'VIEWER']],
  ])('is read-only for a %s', async (_label, roles) => {
    const { mount } = setup({}, roles)
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="new-client-rule"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="edit-meteor"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="delete-meteor"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="read-only"]').text()).toContain('Only administrators')
    expect(wrapper.find('[data-testid="hits-meteor"]').exists(), 'every team role reads the hits').toBe(true)

    wrapper.unmount()
  })

  it('offers create, edit and delete to an administrator', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="new-client-rule"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="edit-meteor"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="delete-meteor"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="read-only"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('ClientRulesView: hits', () => {
  it('lists the hits of a rule: who, when, what the rule did and where, never a client value', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="hits-meteor"]').trigger('click')
    await flushPromises()

    expect(api.clientRuleHits).toHaveBeenCalledWith('meteor')
    const dialog = wrapper.get('[data-testid="hits-dialog"]')
    expect(dialog.findAll('tbody tr')).toHaveLength(3)
    expect(dialog.text()).toContain('Enforced')
    expect(dialog.text()).toContain('Bypassed (staff)')
    expect(dialog.text()).toContain('Team notified')
    expect(dialog.text()).toContain('lobby-1')
    expect(dialog.text()).not.toContain('ab'.repeat(64))
    expect(dialog.get('table a').attributes('href')).toBe(`/players/${'ab'.repeat(64)}`)

    wrapper.unmount()
  })

  it('says so when a rule has no recorded hits and when loading them fails', async () => {
    const clientRuleHits = vi.fn().mockResolvedValueOnce(ok([])).mockResolvedValueOnce(fail('boom', 500, 'UNKNOWN'))
    const { mount } = setup({ clientRuleHits })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="hits-meteor"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="hits-empty"]').text()).toContain('No recorded hits')

    await wrapper.get('[data-testid="close-hits"]').trigger('click')
    await wrapper.get('[data-testid="hits-no-op"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="hits-error"]').attributes('role')).toBe('alert')

    wrapper.unmount()
  })
})

describe('ClientRulesView: delete', () => {
  it('asks first, then deletes and reloads', async () => {
    const clientRules = vi.fn().mockResolvedValueOnce(ok([watching, denying])).mockResolvedValueOnce(ok([denying]))
    const { api, mount } = setup({ clientRules })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="delete-meteor"]').trigger('click')
    expect(api.deleteClientRule).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.deleteClientRule).toHaveBeenCalledWith('meteor')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    expect(wrapper.get('[role="status"]').text()).toContain('Rule deleted.')

    wrapper.unmount()
  })

  it('keeps the dialog open and shows the reason when the delete fails', async () => {
    const { mount } = setup({ deleteClientRule: vi.fn(async () => fail('there is no rule with key meteor', 404, 'NOT_FOUND')) })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="delete-meteor"]').trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="dialog"] [data-testid="error"]').text()).toContain('no rule with key')

    wrapper.unmount()
  })
})

describe('ClientRulesView: editor', () => {
  async function openNew(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']) {
    await wrapper.get('[data-testid="new-client-rule"]').trigger('click')
    await flushPromises()
  }

  it('creates a channel rule that notifies by default and reloads the list', async () => {
    const clientRules = vi.fn().mockResolvedValueOnce(ok([])).mockResolvedValueOnce(ok([watching]))
    const { api, mount } = setup({ clientRules })
    const { wrapper } = await mount()

    await openNew(wrapper)
    await field(wrapper, 'Key').setValue('meteor')
    await field(wrapper, 'Name').setValue('Meteor client')
    await field(wrapper, 'Patterns').setValue('meteor-client:*')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createClientRule).toHaveBeenCalledWith(expect.objectContaining({
      key: 'meteor', kind: 'CHANNEL', patterns: ['meteor-client:*'], action: 'NOTIFY', scope: 'GLOBAL', acknowledgedSpoofable: false,
    }))
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    expect(wrapper.get('[role="status"]').text()).toContain('Rule saved.')

    wrapper.unmount()
  })

  it('shows the fake-ability note for brand, mod and channel rules but not for protocol and command rules', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNew(wrapper)

    expect(wrapper.find('[data-testid="spoof-note"]').exists()).toBe(true)
    await chooseOption(combo(wrapper, 'What the rule matches'), 'PROTOCOL')
    expect(wrapper.find('[data-testid="spoof-note"]').exists()).toBe(false)
    await chooseOption(combo(wrapper, 'What the rule matches'), 'COMMAND')
    expect(wrapper.find('[data-testid="spoof-note"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="hide-command"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('edits a protocol rule with its range and the supported versions as a reference', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-old"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="supported-versions"]').text()).toContain('767 (1.21-1.21.1)')
    await field(wrapper, 'Lowest protocol version').setValue('100')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateClientRule).toHaveBeenCalledWith(expect.objectContaining({ key: 'old', kind: 'PROTOCOL', protocolMin: 100, protocolMax: 340, negate: true }))

    wrapper.unmount()
  })

  it('rejects a protocol number that is not a whole number without calling the API', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-old"]').trigger('click')
    await flushPromises()

    await field(wrapper, 'Highest protocol version').setValue('3.5')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="dialog"]').text()).toContain('Enter a whole number')
    expect(api.updateClientRule).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('limits a rule to sections and reuses the section target picker for CloudNet servers', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNew(wrapper)
    await field(wrapper, 'Key').setValue('mini-meteor')
    await field(wrapper, 'Name').setValue('Meteor on minigames')
    await field(wrapper, 'Patterns').setValue('meteor-client:*')

    await chooseOption(combo(wrapper, 'Where it applies'), 'SERVERS')
    await wrapper.get('[data-testid="section-minigames"] input').setValue(true)
    await chooseOption(combo(wrapper, 'Own servers are'), 'CLOUDNET_TASK')
    expect(wrapper.find('[data-testid="cloudnet-picker"]').exists()).toBe(true)
    await field(wrapper, 'Add a name').setValue('BedWars')
    await wrapper.get('[data-testid="add-name"]').trigger('click')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createClientRule).toHaveBeenCalledWith(expect.objectContaining({
      scope: 'SERVERS', sectionKeys: ['minigames'], targetKind: 'CLOUDNET_TASK', servers: ['BedWars'],
    }))

    wrapper.unmount()
  })

  async function protocolRule(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']) {
    await openNew(wrapper)
    await chooseOption(combo(wrapper, 'What the rule matches'), 'PROTOCOL')
    await field(wrapper, 'Key').setValue('old')
    await field(wrapper, 'Name').setValue('Old')
    await field(wrapper, 'Lowest protocol version').setValue('1')
    await field(wrapper, 'Highest protocol version').setValue('340')
  }

  it('asks for the message a denied player sees when the action is deny', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await protocolRule(wrapper)

    expect(wrapper.text()).not.toContain('Message for the player')
    await chooseOption(combo(wrapper, 'What it does'), 'DENY')
    await field(wrapper, 'Message for the player').setValue('Please update.')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createClientRule).toHaveBeenCalledWith(expect.objectContaining({ action: 'DENY', denyMessage: 'Please update.' }))

    wrapper.unmount()
  })

  it('asks for a template when the action punishes, without an acknowledgement for a protocol rule', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await protocolRule(wrapper)

    await chooseOption(combo(wrapper, 'What it does'), 'TEMPLATE')
    await chooseOption(combo(wrapper, 'Punishment template'), 'old-client')
    expect(wrapper.find('[data-testid="spoof-warning"]').exists(), 'the protocol is observed by the proxy, not announced by the client').toBe(false)
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createClientRule).toHaveBeenCalledWith(expect.objectContaining({ action: 'TEMPLATE', templateKey: 'old-client', acknowledgedSpoofable: false }))

    wrapper.unmount()
  })

  async function brandRule(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']) {
    await openNew(wrapper)
    await chooseOption(combo(wrapper, 'What the rule matches'), 'BRAND')
    await field(wrapper, 'Key').setValue('brand')
    await field(wrapper, 'Name').setValue('Brand')
    await field(wrapper, 'Patterns').setValue('cheat*')
    await chooseOption(combo(wrapper, 'What it does'), 'TEMPLATE')
    await chooseOption(combo(wrapper, 'Punishment template'), 'old-client')
  }

  it('will not punish on a brand, mod or channel without the acknowledgement', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await brandRule(wrapper)

    expect(wrapper.get('[data-testid="spoof-warning"]').text()).toContain('Anybody can fake it')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(api.createClientRule).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="spoof-warning"]').text()).toContain('Confirm that you know')

    await wrapper.get('[data-testid="ack"] input').setValue(true)
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(api.createClientRule).toHaveBeenCalledWith(expect.objectContaining({ kind: 'BRAND', action: 'TEMPLATE', acknowledgedSpoofable: true }))

    wrapper.unmount()
  })

  it('withdraws the acknowledgement when the action or the kind changes', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await brandRule(wrapper)
    await wrapper.get('[data-testid="ack"] input').setValue(true)

    await chooseOption(combo(wrapper, 'What it does'), 'DENY')
    await chooseOption(combo(wrapper, 'What it does'), 'TEMPLATE')
    expect((wrapper.get('[data-testid="spoof-warning"] input[type="checkbox"]').element as HTMLInputElement).checked, 'after an action change').toBe(false)

    await wrapper.get('[data-testid="ack"] input').setValue(true)
    await chooseOption(combo(wrapper, 'What the rule matches'), 'CHANNEL')
    expect((wrapper.get('[data-testid="spoof-warning"] input[type="checkbox"]').element as HTMLInputElement).checked, 'after a kind change').toBe(false)

    wrapper.unmount()
  })

  it('suggests what the proxies saw and lets the admin type anything when there is no catalogue', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNew(wrapper)

    await wrapper.get('[data-testid="suggest-meteor-client:main"]').trigger('click')
    expect((field(wrapper, 'Patterns').element as HTMLInputElement).value).toBe('meteor-client:main')
    expect(wrapper.find('[data-testid="suggest-meteor-client:main"]').exists(), 'a value already in the list is not suggested again').toBe(false)
    wrapper.unmount()

    const offline = setup({ clientRuleCatalogue: vi.fn(async () => fail('cache down', 503, 'UNKNOWN')) })
    const open = await offline.mount()
    await openNew(open.wrapper)
    expect(open.wrapper.get('[data-testid="suggestions-missing"]').text()).toContain('Type the values yourself')
    await field(open.wrapper, 'Patterns').setValue('anything:*')
    expect((field(open.wrapper, 'Patterns').element as HTMLInputElement).value).toBe('anything:*')
    open.wrapper.unmount()
  })

  it('shows the backend validation error next to the field and keeps the dialog open', async () => {
    const createClientRule = vi.fn(async () => fail("patterns: 'not valid!' may only contain a-z", 400, 'VALIDATION'))
    const { mount } = setup({ createClientRule })
    const { wrapper } = await mount()
    await openNew(wrapper)
    await field(wrapper, 'Key').setValue('bad')
    await field(wrapper, 'Name').setValue('Bad')
    await field(wrapper, 'Patterns').setValue('not valid!')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    const patterns = field(wrapper, 'Patterns')
    expect(patterns.attributes('aria-invalid')).toBe('true')
    expect(wrapper.get(`[id="${patterns.attributes('aria-describedby')}"]`).text()).toContain('may only contain')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('fixes the key of an existing rule and can switch it off', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-meteor"]').trigger('click')
    await flushPromises()

    expect(field(wrapper, 'Key').attributes('disabled')).toBeDefined()
    await wrapper.get('[role="dialog"] input[type="checkbox"]:last-of-type').setValue(false)
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateClientRule).toHaveBeenCalledWith(expect.objectContaining({ key: 'meteor', enabled: false }))

    wrapper.unmount()
  })
})
