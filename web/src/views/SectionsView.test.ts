import { flushPromises } from '@vue/test-utils'
import { chooseOption } from '../test/select'
import { describe, expect, it, vi } from 'vitest'
import type { Section } from '../api/types'
import DiscordLinksPanel from '../components/account/DiscordLinksPanel.vue'
import StaffLinksPanel from '../components/account/StaffLinksPanel.vue'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import SectionsView from './SectionsView.vue'

function section(overrides: Partial<Section> = {}): Section {
  return {
    key: 'network',
    displayName: 'Network',
    enforcement: 'BAN',
    targetKind: 'PATTERN',
    servers: [],
    baseScore: 1000,
    softThreshold: 700,
    softDuration: 'P3D',
    permanentThreshold: 300,
    pardonScore: 700,
    permanentReason: 'Banned from the network',
    decayPerDay: 10,
    enabled: true,
    ...overrides,
  }
}

const chat = section({ key: 'chat', displayName: 'Chat', enforcement: 'MUTE', softDuration: 'PT24H', permanentReason: 'Muted for good' })

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    sections: vi.fn(async () => ok([chat, section()])),
    createSection: vi.fn(async () => ok(section())),
    updateSection: vi.fn(async () => ok(section())),
    disableSection: vi.fn(async () => ok(section())),
    staffLinks: vi.fn(async () => ok([])),
    discordLinks: vi.fn(async () => ok([])),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(SectionsView, { api: mockApi(api), path: '/sections' }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

/** The table row of a section, found by the key in its first cell. */
function rowOf(wrapper: Wrapper, key: string) {
  const row = wrapper.findAll('tbody tr').find((candidate) => candidate.get('td').text().startsWith(key))
  if (!row) throw new Error(`no row for ${key}`)
  return row
}

/** Chooses an item of a section's row menu, which holds the actions beyond edit. */
async function rowAction(wrapper: Wrapper, key: string, label: string): Promise<void> {
  await rowOf(wrapper, key).get('button[aria-haspopup="menu"]').trigger('click')
  const item = wrapper.findAll('[role="menuitem"]').find((candidate) => candidate.text() === label)
  if (!item) throw new Error(`no menu item ${label}`)
  await item.trigger('click')
}

const field = (wrapper: Wrapper, label: string) => {
  const found = wrapper.findAll('[role="dialog"] label').find((l) => l.text().startsWith(label))
  if (!found) throw new Error(`no field labelled ${label}`)
  return wrapper.get(`[role="dialog"] [id="${found.attributes('for')}"]`)
}

describe('SectionsView: list', () => {
  it('lists the sections with enforcement and thresholds', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[1]!.text()).toContain('network')
    expect(rows[1]!.text()).toContain('Ban')
    expect(rows[1]!.text()).toContain('300')
    expect(rows[1]!.text()).toContain('700')

    wrapper.unmount()
  })

  it('shows the server patterns of a server section and marks disabled sections', async () => {
    const bedwars = section({ key: 'bedwars', displayName: 'Bedwars', enforcement: 'SERVER', servers: ['bedwars-*', 'bw-*'] })
    const old = section({ key: 'old', displayName: 'Old', enabled: false })
    const { mount } = setup({ sections: vi.fn(async () => ok([bedwars, old])) })
    const { wrapper } = await mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('bedwars-*')
    expect(rows[0]!.text()).toContain('bw-*')
    expect(rows[1]!.text()).toContain('Disabled')
    expect(rowOf(wrapper, 'old').find('button[aria-haspopup="menu"]').exists(), 'nothing to disable').toBe(false)
    expect(rowOf(wrapper, 'bedwars').find('button[aria-haspopup="menu"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows the error with a retry when loading fails', async () => {
    const sections = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok([chat]))
    const { mount } = setup({ sections })
    const { wrapper } = await mount()

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('tbody tr')).toHaveLength(1)

    wrapper.unmount()
  })
})

describe('SectionsView: edit', () => {
  it('changes the permanent threshold and shows the new value', async () => {
    const changed = section({ permanentThreshold: 250 })
    const sections = vi.fn().mockResolvedValueOnce(ok([chat, section()])).mockResolvedValueOnce(ok([chat, changed]))
    const { api, mount } = setup({ sections })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="edit-network"]').trigger('click')
    expect(field(wrapper, 'Key').attributes('disabled'), 'the key of an existing section is fixed').toBeDefined()
    await field(wrapper, 'Permanent threshold').setValue('250')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateSection).toHaveBeenCalledWith({
      key: 'network',
      displayName: 'Network',
      enforcement: 'BAN',
      targetKind: 'PATTERN',
      servers: [],
      baseScore: 1000,
      softThreshold: 700,
      softDuration: 'P3D',
      permanentThreshold: 250,
      pardonScore: 700,
      permanentReason: 'Banned from the network',
      decayPerDay: 10,
      enabled: true,
    })
    expect(wrapper.findAll('tbody tr')[1]!.text()).toContain('250')
    expect(wrapper.get('[role="status"]').text()).toContain('Section saved.')

    wrapper.unmount()
  })

  it('shows the backend validation error next to the permanent threshold and keeps the dialog open', async () => {
    const updateSection = vi.fn(async () => fail('permanentThreshold must be lower than baseScore', 400, 'VALIDATION'))
    const { mount } = setup({ updateSection })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-network"]').trigger('click')
    await field(wrapper, 'Permanent threshold').setValue('5000')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    const threshold = field(wrapper, 'Permanent threshold')
    expect(threshold.attributes('aria-invalid')).toBe('true')
    expect(wrapper.get(`[id="${threshold.attributes('aria-describedby')}"]`).text()).toContain('must be lower than baseScore')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('clears the soft threshold and its duration when the field is emptied', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-network"]').trigger('click')

    await field(wrapper, 'Soft threshold').setValue('')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateSection).toHaveBeenCalledWith(expect.objectContaining({ softThreshold: null, softDuration: null }))

    wrapper.unmount()
  })

  it('rejects a number field that is not a whole number without calling the API', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-network"]').trigger('click')

    await field(wrapper, 'Base score').setValue('1.5')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Enter a whole number.')
    expect(api.updateSection).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('can re-enable a disabled section', async () => {
    const old = section({ key: 'old', displayName: 'Old', enabled: false })
    const { api, mount } = setup({ sections: vi.fn(async () => ok([old])) })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="edit-old"]').trigger('click')
    await wrapper.get('[role="dialog"] input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateSection).toHaveBeenCalledWith(expect.objectContaining({ key: 'old', enabled: true }))

    wrapper.unmount()
  })
})

describe('SectionsView: create', () => {
  async function openNew(wrapper: Wrapper) {
    await wrapper.get('[data-testid="new-section"]').trigger('click')
  }

  it('creates a server section with its patterns and lists it', async () => {
    const bedwars = section({ key: 'bedwars', displayName: 'Bedwars', enforcement: 'SERVER', servers: ['bedwars-*'] })
    const sections = vi.fn().mockResolvedValueOnce(ok([chat])).mockResolvedValueOnce(ok([chat, bedwars]))
    const { api, mount } = setup({ sections })
    const { wrapper } = await mount()

    await openNew(wrapper)
    await field(wrapper, 'Key').setValue('bedwars')
    await field(wrapper, 'Name').setValue('Bedwars')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'SERVER')
    await field(wrapper, 'Server patterns').setValue('bedwars-*')
    await field(wrapper, 'Base score').setValue('1000')
    await field(wrapper, 'Permanent threshold').setValue('300')
    await field(wrapper, 'Pardon score').setValue('700')
    await field(wrapper, 'Permanent reason').setValue('Banned from bedwars')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith({
      key: 'bedwars',
      displayName: 'Bedwars',
      enforcement: 'SERVER',
      targetKind: 'PATTERN',
      servers: ['bedwars-*'],
      baseScore: 1000,
      softThreshold: null,
      softDuration: null,
      permanentThreshold: 300,
      pardonScore: 700,
      permanentReason: 'Banned from bedwars',
      decayPerDay: 10,
      enabled: true,
    })
    const created = wrapper.findAll('tbody tr').find((row) => row.text().includes('bedwars'))
    expect(created?.text()).toContain('bedwars-*')

    wrapper.unmount()
  })

  it('offers server patterns only for a server section', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNew(wrapper)

    expect(wrapper.get('[role="dialog"]').text()).not.toContain('Server patterns')

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'SERVER')
    expect(wrapper.get('[role="dialog"]').text()).toContain('Server patterns')

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'MUTE')
    expect(wrapper.get('[role="dialog"]').text()).not.toContain('Server patterns')

    wrapper.unmount()
  })

  it('splits patterns on commas and ignores blanks', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNew(wrapper)
    await field(wrapper, 'Key').setValue('arena')
    await field(wrapper, 'Name').setValue('Arena')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'SERVER')
    await field(wrapper, 'Server patterns').setValue(' arena-* , pvp-? ,, ')
    await field(wrapper, 'Permanent reason').setValue('x')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ servers: ['arena-*', 'pvp-?'] }))

    wrapper.unmount()
  })

  it('shows a taken key next to the key field', async () => {
    const createSection = vi.fn(async () => fail("a section with key 'chat' already exists", 409, 'CONFLICT'))
    const { mount } = setup({ createSection })
    const { wrapper } = await mount()
    await openNew(wrapper)
    await field(wrapper, 'Key').setValue('chat')
    await field(wrapper, 'Name').setValue('Chat')
    await field(wrapper, 'Permanent reason').setValue('x')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    const key = field(wrapper, 'Key')
    expect(wrapper.get(`[id="${key.attributes('aria-describedby')}"]`).text()).toContain('already exists')

    wrapper.unmount()
  })
})

describe('SectionsView: disable', () => {
  it('disables a section after confirmation and reloads', async () => {
    const sections = vi
      .fn()
      .mockResolvedValueOnce(ok([chat, section()]))
      .mockResolvedValueOnce(ok([chat, section({ enabled: false })]))
    const { api, mount } = setup({ sections })
    const { wrapper } = await mount()

    await rowAction(wrapper, 'network', 'Disable')
    expect(api.disableSection, 'asks first').not.toHaveBeenCalled()
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.disableSection).toHaveBeenCalledWith('network')
    expect(wrapper.findAll('tbody tr')[1]!.text()).toContain('Disabled')

    wrapper.unmount()
  })

  it('shows why disabling failed', async () => {
    const disableSection = vi.fn(async () => fail("there is no section with key 'network'", 404, 'NOT_FOUND'))
    const { mount } = setup({ disableSection })
    const { wrapper } = await mount()

    await rowAction(wrapper, 'network', 'Disable')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('no section with key')

    wrapper.unmount()
  })
})

describe('SectionsView: page template', () => {
  it('no longer renders the account link panels on the sections page', async () => {
    const { api } = setup()
    const { wrapper } = await mountWithApp(SectionsView, { api: mockApi(api), path: '/sections', roles: ['ADMIN', 'MODERATOR', 'VIEWER'] })

    expect(wrapper.findComponent(StaffLinksPanel).exists()).toBe(false)
    expect(wrapper.findComponent(DiscordLinksPanel).exists()).toBe(false)

    wrapper.unmount()
  })

  it('keeps every table cell to one kind of content: no definition lists', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.find('tbody dl').exists()).toBe(false)

    wrapper.unmount()
  })

  it('keeps edit in the row and moves disable into the row menu', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    const row = wrapper.findAll('tbody tr')[1]!

    expect(row.find('[data-testid="edit-network"]').exists()).toBe(true)
    expect(row.find('[data-testid="disable-network"]').exists(), 'disable is not a row button').toBe(false)
    await row.get('button[aria-haspopup="menu"]').trigger('click')
    expect(wrapper.findAll('[role="menuitem"]').map((item) => item.text())).toEqual(['Disable'])

    wrapper.unmount()
  })

  it('confirms the disable with the error button', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await wrapper.findAll('tbody tr')[1]!.get('button[aria-haspopup="menu"]').trigger('click')
    await wrapper.get('[role="menuitem"]').trigger('click')

    expect(wrapper.get('[data-testid="confirm"]').classes(), 'destructive confirm').toContain('bg-error')

    wrapper.unmount()
  })
})
