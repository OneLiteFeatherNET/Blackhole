import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CloudNetCatalogue, Section } from '../api/types'
import { formatDateTime } from '../format'
import { chooseOption, listOptions } from '../test/select'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import SectionsView from './SectionsView.vue'

const REPORTED_AT = '2026-10-23T12:00:00Z'

const catalogue: CloudNetCatalogue = {
  available: true,
  reportedAt: REPORTED_AT,
  tasks: [
    { name: 'BedWars', groups: ['Games'] },
    { name: 'SkyWars', groups: [] },
  ],
  groups: ['Games', 'Lobbies'],
  services: [
    { name: 'BedWars-1', task: 'BedWars', running: true },
    { name: 'BedWars-2', task: 'BedWars', running: false },
  ],
  proxies: ['Proxy-1', 'Proxy-2'],
}

function section(overrides: Partial<Section> = {}): Section {
  return {
    key: 'games',
    displayName: 'Games',
    enforcement: 'SERVER',
    targetKind: 'PATTERN',
    servers: ['games-*'],
    baseScore: 1000,
    softThreshold: null,
    softDuration: null,
    permanentThreshold: 300,
    pardonScore: 700,
    permanentReason: 'Banned from the games',
    decayPerDay: 10,
    enabled: true,
    ...overrides,
  }
}

function setup(options: { sections?: Section[]; catalogue?: unknown; roles?: string[]; createSection?: unknown } = {}) {
  const api = {
    sections: vi.fn(async () => ok(options.sections ?? [section()])),
    cloudnetCatalogue: vi.fn(async () => options.catalogue ?? ok(catalogue)),
    createSection: options.createSection ?? vi.fn(async () => ok(section())),
    updateSection: vi.fn(async () => ok(section())),
    disableSection: vi.fn(async () => ok(section())),
    staffLinks: vi.fn(async () => ok([])),
  }
  return { api, mount: () => mountWithApp(SectionsView, { api: mockApi(api), path: '/sections', roles: options.roles }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

const dialog = (wrapper: Wrapper) => wrapper.get('[role="dialog"]')
const targetSelect = (wrapper: Wrapper) => dialog(wrapper).findAll('[role="combobox"]')[1]!
const field = (wrapper: Wrapper, label: string) => {
  const found = wrapper.findAll('[role="dialog"] label').find((l) => l.text().startsWith(label))
  if (!found) throw new Error(`no field labelled ${label}`)
  return wrapper.get(`[role="dialog"] [id="${found.attributes('for')}"]`)
}

async function openNewServerSection(wrapper: Wrapper): Promise<void> {
  await wrapper.get('[data-testid="new-section"]').trigger('click')
  await field(wrapper, 'Key').setValue('games')
  await field(wrapper, 'Name').setValue('Games')
  await field(wrapper, 'Permanent reason').setValue('Banned from the games')
  await chooseOption(dialog(wrapper).get('[role="combobox"]'), 'SERVER')
}

async function chooseKind(wrapper: Wrapper, kind: string): Promise<void> {
  await chooseOption(targetSelect(wrapper), kind)
  await flushPromises()
}

describe('SectionsView: target kind selector', () => {
  it('offers the four kinds and defaults to name patterns', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    const options = await listOptions(targetSelect(wrapper))

    expect(options.map((option) => option.value)).toEqual(['PATTERN', 'CLOUDNET_TASK', 'CLOUDNET_GROUP', 'CLOUDNET_SERVICE'])
    expect(options.map((option) => option.label)).toEqual(['Name pattern', 'CloudNet task', 'CloudNet group', 'CloudNet service'])
    expect(targetSelect(wrapper).text()).toContain('Name pattern')
    expect(dialog(wrapper).text()).toContain('Server patterns')
    expect(dialog(wrapper).find('[data-testid="cloudnet-picker"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('does not offer a target for a section that is not a server section', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="new-section"]').trigger('click')

    expect(dialog(wrapper).findAll('[role="combobox"]')).toHaveLength(1)

    wrapper.unmount()
  })

  it('saves name patterns with kind PATTERN', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)
    await field(wrapper, 'Server patterns').setValue('games-*, pvp-?')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ targetKind: 'PATTERN', servers: ['games-*', 'pvp-?'] }))
    expect(api.cloudnetCatalogue, 'patterns never need the catalogue').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('shows the pattern input again with its text after switching back to patterns', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)
    await field(wrapper, 'Server patterns').setValue('games-*')

    await chooseKind(wrapper, 'CLOUDNET_TASK')
    expect(dialog(wrapper).text()).not.toContain('Server patterns')
    await chooseKind(wrapper, 'PATTERN')

    expect((field(wrapper, 'Server patterns').element as HTMLInputElement).value).toBe('games-*')
    expect(dialog(wrapper).find('[data-testid="cloudnet-picker"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('SectionsView: picker with a catalogue', () => {
  it('lists the tasks and saves a chosen task as CLOUDNET_TASK', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, 'CLOUDNET_TASK')

    const picker = wrapper.get('[data-testid="cloudnet-picker"]')
    expect(picker.text()).toContain('CloudNet tasks')
    expect(picker.text()).toContain('BedWars')
    expect(picker.text()).toContain('SkyWars')
    expect(picker.text()).toContain('groups: Games')
    expect(picker.text(), 'groups and services are not offered for a task').not.toContain('Lobbies')

    await picker.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ enforcement: 'SERVER', targetKind: 'CLOUDNET_TASK', servers: ['BedWars'] }))

    wrapper.unmount()
  })

  it('lists the groups and saves a chosen group as CLOUDNET_GROUP', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, 'CLOUDNET_GROUP')

    const picker = wrapper.get('[data-testid="cloudnet-picker"]')
    expect(picker.text()).toContain('Lobbies')
    expect(picker.text()).not.toContain('SkyWars')

    await picker.findAll('input[type="checkbox"]')[1]!.setValue(true)
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ targetKind: 'CLOUDNET_GROUP', servers: ['Lobbies'] }))

    wrapper.unmount()
  })

  it('lists the services with their state and saves a chosen service as CLOUDNET_SERVICE', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, 'CLOUDNET_SERVICE')

    const picker = wrapper.get('[data-testid="cloudnet-picker"]')
    expect(picker.text()).toContain('BedWars-1')
    expect(picker.text()).toContain('running, task BedWars')
    expect(picker.text(), 'the state is written out, not only coloured').toContain('stopped, task BedWars')

    await picker.findAll('input[type="checkbox"]')[1]!.setValue(true)
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ targetKind: 'CLOUDNET_SERVICE', servers: ['BedWars-2'] }))

    wrapper.unmount()
  })

  it('names the proxies that report the catalogue and when it was reported', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, 'CLOUDNET_TASK')

    const source = wrapper.get('[data-testid="catalogue-source"]').text()
    expect(source).toContain('Proxy-1, Proxy-2')
    expect(source).toContain(formatDateTime(Date.parse(REPORTED_AT), 'en'))

    wrapper.unmount()
  })

  it('asks for the catalogue once per opened editor', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, 'CLOUDNET_TASK')
    await chooseKind(wrapper, 'CLOUDNET_GROUP')

    expect(api.cloudnetCatalogue).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('narrows the offered names with the filter', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)
    await chooseKind(wrapper, 'CLOUDNET_TASK')

    await field(wrapper, 'Filter names').setValue('sky')

    const boxes = wrapper.get('[data-testid="cloudnet-picker"]').findAll('input[type="checkbox"]')
    expect(boxes).toHaveLength(1)

    wrapper.unmount()
  })

  it('starts empty when the kind changes between CloudNet kinds', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)
    await chooseKind(wrapper, 'CLOUDNET_TASK')
    await wrapper.get('[data-testid="cloudnet-picker"]').get('input[type="checkbox"]').setValue(true)

    await chooseKind(wrapper, 'CLOUDNET_GROUP')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ targetKind: 'CLOUDNET_GROUP', servers: [] }))

    wrapper.unmount()
  })
})

describe('SectionsView: names that are not in the catalogue', () => {
  it('marks a typed name that the catalogue does not list and still saves it', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)
    await chooseKind(wrapper, 'CLOUDNET_TASK')

    await field(wrapper, 'Add a name').setValue('Future')
    await wrapper.get('[data-testid="add-name"]').trigger('click')

    const chosen = wrapper.get('[data-testid="chosen"]')
    expect(chosen.text()).toContain('Future')
    expect(chosen.text(), 'marked in words').toContain('Not in catalogue')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ targetKind: 'CLOUDNET_TASK', servers: ['Future'] }))

    wrapper.unmount()
  })

  it('does not mark a typed name that the catalogue lists, whatever its case', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)
    await chooseKind(wrapper, 'CLOUDNET_TASK')

    await field(wrapper, 'Add a name').setValue('bedwars')
    await wrapper.get('[data-testid="add-name"]').trigger('click')

    expect(wrapper.get('[data-testid="chosen"]').text()).not.toContain('Not in catalogue')

    wrapper.unmount()
  })

  it('marks a saved name that the catalogue no longer lists when editing', async () => {
    const saved = section({ targetKind: 'CLOUDNET_TASK', servers: ['BedWars', 'Gone'] })
    const { api, mount } = setup({ sections: [saved] })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="edit-games"]').trigger('click')
    await flushPromises()

    const items = wrapper.get('[data-testid="chosen"]').findAll('li')
    expect(items[0]!.text()).not.toContain('Not in catalogue')
    expect(items[1]!.text()).toContain('Not in catalogue')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()
    expect(api.updateSection).toHaveBeenCalledWith(expect.objectContaining({ targetKind: 'CLOUDNET_TASK', servers: ['BedWars', 'Gone'] }))

    wrapper.unmount()
  })

  it('adds a typed name with Enter without submitting the form', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)
    await chooseKind(wrapper, 'CLOUDNET_GROUP')

    await field(wrapper, 'Add a name').trigger('keydown', { key: 'Enter' })
    expect(wrapper.get('[data-testid="cloudnet-picker"]').text(), 'an empty draft adds nothing').toContain('No name chosen yet.')
    await field(wrapper, 'Add a name').setValue('Extra')
    await field(wrapper, 'Add a name').trigger('keydown', { key: 'Enter' })

    expect(wrapper.get('[data-testid="chosen"]').text()).toContain('Extra')
    expect(api.createSection, 'Enter adds the name, it does not save').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('saves a name that was typed but not added yet', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)
    await chooseKind(wrapper, 'CLOUDNET_GROUP')

    await field(wrapper, 'Add a name').setValue('Pending')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ servers: ['Pending'] }))

    wrapper.unmount()
  })

  it('removes a chosen name with a button that names it', async () => {
    const saved = section({ targetKind: 'CLOUDNET_GROUP', servers: ['Games', 'Lobbies'] })
    const { api, mount } = setup({ sections: [saved] })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-games"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="chosen"] button[aria-label="Remove Games"]').trigger('click')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateSection).toHaveBeenCalledWith(expect.objectContaining({ servers: ['Lobbies'] }))

    wrapper.unmount()
  })
})

describe('SectionsView: free-text fallback', () => {
  it('works as free text with a hint when no CloudNet data was reported', async () => {
    const empty: CloudNetCatalogue = { available: false, tasks: [], groups: [], services: [], proxies: [] }
    const { api, mount } = setup({ catalogue: ok(empty) })
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, 'CLOUDNET_GROUP')

    const picker = wrapper.get('[data-testid="cloudnet-picker"]')
    expect(picker.get('[data-testid="catalogue-missing"]').text()).toContain('No CloudNet data was reported')
    expect(picker.find('input[type="checkbox"]').exists()).toBe(false)

    await field(wrapper, 'Add a name').setValue('Lobbies')
    await wrapper.get('[data-testid="add-name"]').trigger('click')
    expect(wrapper.get('[data-testid="chosen"]').text(), 'nothing can be called missing without a catalogue').not.toContain('Not in catalogue')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ targetKind: 'CLOUDNET_GROUP', servers: ['Lobbies'] }))

    wrapper.unmount()
  })

  it('works as free text when the catalogue request fails, without blocking the form', async () => {
    const { api, mount } = setup({ catalogue: fail('backend down', 0, 'NETWORK') })
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, 'CLOUDNET_TASK')

    expect(wrapper.get('[data-testid="catalogue-missing"]').text()).toContain('No CloudNet data was reported')
    expect(wrapper.find('[data-testid="error"]').exists(), 'a missing catalogue is not a form error').toBe(false)

    await field(wrapper, 'Add a name').setValue('BedWars')
    await wrapper.get('[data-testid="add-name"]').trigger('click')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createSection).toHaveBeenCalledWith(expect.objectContaining({ targetKind: 'CLOUDNET_TASK', servers: ['BedWars'] }))

    wrapper.unmount()
  })

  it('says so when the catalogue knows none of the chosen kind', async () => {
    const onlyGroups: CloudNetCatalogue = { ...catalogue, tasks: [] }
    const { mount } = setup({ catalogue: ok(onlyGroups) })
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, 'CLOUDNET_TASK')

    expect(wrapper.get('[data-testid="catalogue-missing"]').text()).toContain('none of this kind')

    wrapper.unmount()
  })

  it('shows the backend validation error of the names inside the picker', async () => {
    const createSection = vi.fn(async () => fail("server name 'Bed*' may only contain letters, digits, '_', '.' and '-' (no wildcards)", 400, 'VALIDATION'))
    const { mount } = setup({ createSection })
    const mounted = await mount()
    await openNewServerSection(mounted.wrapper)
    await chooseKind(mounted.wrapper, 'CLOUDNET_TASK')
    await field(mounted.wrapper, 'Add a name').setValue('Bed*')
    await mounted.wrapper.get('[data-testid="add-name"]').trigger('click')

    await mounted.wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(mounted.wrapper.get('[data-testid="cloudnet-picker"] [role="alert"]').text()).toContain('no wildcards')
    expect(mounted.wrapper.find('[role="dialog"]').exists(), 'the dialog stays open').toBe(true)

    mounted.wrapper.unmount()
  })
})

describe('SectionsView: service warning', () => {
  it('warns before saving that the ban follows the service name', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, 'CLOUDNET_SERVICE')

    const warning = wrapper.get('[data-testid="service-warning"]')
    expect(warning.attributes('role')).toBe('note')
    expect(warning.text()).toContain('Warning:')
    expect(warning.text()).toContain('follows the name, not the instance')

    wrapper.unmount()
  })

  it.each(['PATTERN', 'CLOUDNET_TASK', 'CLOUDNET_GROUP'])('does not warn for %s', async (kind) => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)

    await chooseKind(wrapper, kind)

    expect(wrapper.find('[data-testid="service-warning"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('removes the warning again when switching away from the service kind', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await openNewServerSection(wrapper)
    await chooseKind(wrapper, 'CLOUDNET_SERVICE')

    await chooseKind(wrapper, 'CLOUDNET_TASK')

    expect(wrapper.find('[data-testid="service-warning"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('SectionsView: list shows the target kind', () => {
  it.each([
    ['PATTERN', 'Name pattern'],
    ['CLOUDNET_TASK', 'CloudNet task'],
    ['CLOUDNET_GROUP', 'CloudNet group'],
    ['CLOUDNET_SERVICE', 'CloudNet service'],
  ] as const)('shows %s as "%s" next to the names', async (targetKind, label) => {
    const { mount } = setup({ sections: [section({ targetKind, servers: ['BedWars'] })] })
    const { wrapper } = await mount()

    const row = wrapper.get('tbody tr')
    expect(row.get('[data-testid="target-kind"]').text()).toBe(label)
    expect(row.text()).toContain('BedWars')

    wrapper.unmount()
  })

  it('shows no kind for a section without server targets', async () => {
    const { mount } = setup({ sections: [section({ enforcement: 'BAN', servers: [] })] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="target-kind"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('SectionsView: roles', () => {
  it('lets an admin open the editor and read the catalogue', async () => {
    const { api, mount } = setup({ sections: [section({ targetKind: 'CLOUDNET_TASK', servers: ['BedWars'] })], roles: ['ADMIN'] })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="edit-games"]').trigger('click')
    await flushPromises()

    expect(api.cloudnetCatalogue).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it.each([
    ['viewer', ['VIEWER']],
    ['moderator', ['MODERATOR']],
  ])('offers a %s no way to edit sections and never asks for the catalogue', async (_label, roles) => {
    const { api, mount } = setup({ sections: [section({ targetKind: 'CLOUDNET_TASK', servers: ['BedWars'] })], roles })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="new-section"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="edit-games"]').exists()).toBe(false)
    expect(wrapper.get('tbody tr').text(), 'reading the kind and names stays possible').toContain('CloudNet task')
    expect(api.cloudnetCatalogue).not.toHaveBeenCalled()
    expect(api.updateSection).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})
