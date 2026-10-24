import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { Section, Template } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption, listOptions } from '../../test/select'
import ImportedPunishmentsPanel from './ImportedPunishmentsPanel.vue'

const templates = [
  { identifier: 'a', key: 'cheating', reason: 'Cheating', section: 'network', steps: [], metaData: {} },
  { identifier: 'c', key: 'chat-spam', reason: 'Spam', section: 'chat', steps: [], metaData: {} },
] as unknown as Template[]
const sections = [
  { key: 'network', enforcement: 'BAN', enabled: true },
  { key: 'chat', enforcement: 'MUTE', enabled: true },
] as unknown as Section[]

const groups = { total: 3, groups: [{ reason: 'Hacking', count: 2 }, { reason: 'Spam', count: 1 }] }

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    importedPunishmentGroups: vi.fn(async () => ok(groups)),
    retemplateImportedPunishments: vi.fn(async (request: { templateKey: string }) =>
      ok({ templateKey: request.templateKey, matched: 2, retemplated: 2, unchanged: 0, skipped: 0 })),
    templates: vi.fn(async () => ok(templates)),
    sections: vi.fn(async () => ok(sections)),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(ImportedPunishmentsPanel, { api: mockApi(api) }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

async function pick(wrapper: Wrapper, reason: string, key: string) {
  await chooseOption(wrapper.get(`[data-testid="pick-${reason}"] [role="combobox"]`), key)
}

describe('ImportedPunishmentsPanel', () => {
  it('lists the imported punishments without a template grouped by reason', async () => {
    const { wrapper } = await setup().mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="imported-total"]').text()).toBe('3 imported punishments have no template.')
    expect(wrapper.text(), 'first group').toContain('Hacking')
    expect(wrapper.text(), 'second group').toContain('Spam')

    wrapper.unmount()
  })

  it('says so when every imported punishment has a template', async () => {
    const { wrapper } = await setup({ importedPunishmentGroups: vi.fn(async () => ok({ total: 0, groups: [] })) }).mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="imported-empty"]').text()).toContain('linked to a template')

    wrapper.unmount()
  })

  it('does not offer templates of mute sections', async () => {
    const { wrapper } = await setup().mount()
    await flushPromises()

    const options = await listOptions(wrapper.get('[data-testid="pick-Hacking"] [role="combobox"]'))

    expect(options.map((option) => option.value)).toEqual(['cheating'])

    wrapper.unmount()
  })

  it('cannot link a group before a template is picked for it', async () => {
    const { wrapper } = await setup().mount()
    await flushPromises()

    const buttons = wrapper.findAll('[data-testid="apply-group"]')

    expect(buttons[0]!.attributes('disabled'), 'first group').toBeDefined()
    await pick(wrapper, 'Hacking', 'cheating')
    expect(wrapper.findAll('[data-testid="apply-group"]')[0]!.attributes('disabled'), 'picked group').toBeUndefined()
    expect(wrapper.findAll('[data-testid="apply-group"]')[1]!.attributes('disabled'), 'other group stays disabled').toBeDefined()

    wrapper.unmount()
  })

  it('asks for confirmation and sends nothing until it is given', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()
    await pick(wrapper, 'Hacking', 'cheating')

    await wrapper.findAll('[data-testid="apply-group"]')[0]!.trigger('click')

    expect(wrapper.find('[role="dialog"]').exists(), 'dialog').toBe(true)
    expect(wrapper.get('[data-testid="confirm-body"]').text(), 'what will happen').toContain('2 imported punishments with the reason “Hacking” are linked to the template cheating')
    expect(api.retemplateImportedPunishments, 'nothing sent').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('links the group after the confirmation and reloads the list', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()
    await pick(wrapper, 'Hacking', 'cheating')
    await wrapper.findAll('[data-testid="apply-group"]')[0]!.trigger('click')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.retemplateImportedPunishments).toHaveBeenCalledWith({ templateKey: 'cheating', reason: 'Hacking' })
    expect(api.importedPunishmentGroups, 'list reloaded').toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog closed').toBe(false)

    wrapper.unmount()
  })

  it('shows the backend message in the dialog when the link is refused and keeps the dialog open', async () => {
    const { mount } = setup({ retemplateImportedPunishments: vi.fn(async () => fail('template is in a mute section', 400)) })
    const { wrapper } = await mount()
    await flushPromises()
    await pick(wrapper, 'Hacking', 'cheating')
    await wrapper.findAll('[data-testid="apply-group"]')[0]!.trigger('click')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('template is in a mute section')
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog stays').toBe(true)

    wrapper.unmount()
  })

  it('offers a retry when the list cannot be loaded', async () => {
    const { wrapper } = await setup({ importedPunishmentGroups: vi.fn(async () => fail('backend down', 0, 'NETWORK')) }).mount()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    expect(wrapper.find('[data-testid="retry"]').exists(), 'retry button').toBe(true)

    wrapper.unmount()
  })
})
