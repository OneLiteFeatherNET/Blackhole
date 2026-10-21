import { flushPromises } from '@vue/test-utils'
import { chooseOption } from '../test/select'
import { describe, expect, it, vi } from 'vitest'
import type { Section } from '../api/types'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import SectionsView from './SectionsView.vue'

function section(overrides: Partial<Section> = {}): Section {
  return {
    key: 'network',
    displayName: 'Network',
    enforcement: 'BAN',
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
    ...overrides,
  }
  return { api, mount: () => mountWithApp(SectionsView, { api: mockApi(api), path: '/sections' }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

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
    expect(wrapper.find('[data-testid="disable-old"]').exists(), 'nothing to disable').toBe(false)
    expect(wrapper.find('[data-testid="disable-bedwars"]').exists()).toBe(true)

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

    await wrapper.get('[data-testid="disable-network"]').trigger('click')
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

    await wrapper.get('[data-testid="disable-network"]').trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('no section with key')

    wrapper.unmount()
  })
})
