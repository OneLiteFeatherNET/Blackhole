import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { Section, Template, VanillaImportResult } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption, listOptions } from '../../test/select'
import VanillaImportPanel from './VanillaImportPanel.vue'

const summary: VanillaImportResult = {
  dryRun: false,
  playersTotal: 5,
  playersImported: 3,
  playersSkippedExisting: 1,
  playersInvalid: 1,
  invalidEntries: ['entry 4: bad uuid'],
  ipsTotal: 2,
  ipsSkipped: 2,
  reasonGroups: [
    { reason: 'Hacking', templateKey: 'cheating', count: 2 },
    { reason: 'Griefing', templateKey: 'cheating', count: 1 },
  ],
}

const templates = [
  { identifier: 'a', key: 'cheating', reason: 'Cheating', section: 'network', steps: [], metaData: {} },
  { identifier: 'b', key: 'griefing', reason: 'Griefing', section: 'network', steps: [], metaData: {} },
  { identifier: 'c', key: 'chat-spam', reason: 'Spam', section: 'chat', steps: [], metaData: {} },
] as unknown as Template[]
const sections = [
  { key: 'network', enforcement: 'BAN', enabled: true },
  { key: 'chat', enforcement: 'MUTE', enabled: true },
] as unknown as Section[]

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    importVanillaBans: vi.fn(async (request: { dryRun: boolean }) => ok({ ...summary, dryRun: request.dryRun })),
    templates: vi.fn(async () => ok(templates)),
    sections: vi.fn(async () => ok(sections)),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(VanillaImportPanel, { api: mockApi(api) }) }
}

async function paste(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper'], text: string) {
  await wrapper.get('[data-testid="paste"]').setValue(text)
}

async function pickTemplate(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper'], key = 'cheating') {
  await chooseOption(wrapper.get('[data-testid="template-picker"] [role="combobox"]'), key)
}

async function readText(blob: Blob): Promise<string> {
  return await new Promise((resolve) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.readAsText(blob)
  })
}

describe('VanillaImportPanel', () => {
  it('needs a template as well as a list before it offers preview or import', async () => {
    const { wrapper } = await setup().mount()
    await paste(wrapper, '[]')

    expect(wrapper.get('[data-testid="preview"]').attributes('disabled'), 'preview without a template').toBeDefined()
    expect(wrapper.get('[data-testid="import"]').attributes('disabled'), 'import without a template').toBeDefined()

    await pickTemplate(wrapper)

    expect(wrapper.get('[data-testid="preview"]').attributes('disabled'), 'preview with a template').toBeUndefined()
    expect(wrapper.get('[data-testid="import"]').attributes('disabled'), 'import with a template').toBeUndefined()

    wrapper.unmount()
  })

  it('offers the templates of ban sections only', async () => {
    const { wrapper } = await setup().mount()

    const options = await listOptions(wrapper.get('[data-testid="template-picker"] [role="combobox"]'))

    expect(options.map((option) => option.value)).toEqual(['cheating', 'griefing'])

    wrapper.unmount()
  })

  it('sends the chosen template and only the complete reason mappings', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await pickTemplate(wrapper)
    await paste(wrapper, '[]')
    await wrapper.get('[data-testid="add-mapping"]').trigger('click')
    await wrapper.get('[data-testid="mapping-0"] input').setValue(' Griefing ')
    await chooseOption(wrapper.get('[data-testid="mapping-0"] [role="combobox"]'), 'griefing')
    await wrapper.get('[data-testid="add-mapping"]').trigger('click')
    await wrapper.get('[data-testid="mapping-1"] input').setValue('Unfinished row')

    await wrapper.get('[data-testid="preview"]').trigger('click')
    await flushPromises()

    const request = api.importVanillaBans.mock.calls[0]![0] as unknown as { templateKey: string; reasonTemplates: Record<string, string> }
    expect(request.templateKey, 'default template').toBe('cheating')
    expect(request.reasonTemplates, 'only the finished row').toEqual({ Griefing: 'griefing' })

    wrapper.unmount()
  })

  it('shows which template each reason gets and can turn a reason into a mapping', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await pickTemplate(wrapper)
    await paste(wrapper, '[]')

    await wrapper.get('[data-testid="preview"]').trigger('click')
    await flushPromises()

    const table = wrapper.get('[data-testid="reason-groups"]')
    expect(table.text(), 'reason and template of each group').toContain('Hacking')
    expect(table.text()).toContain('cheating')
    await table.findAll('[data-testid="map-group"]')[1]!.trigger('click')
    expect((wrapper.get('[data-testid="mapping-0"] input').element as HTMLInputElement).value, 'prefilled reason').toBe('Griefing')

    wrapper.unmount()
  })

  it('names the template in the confirmation', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await pickTemplate(wrapper, 'griefing')
    await paste(wrapper, '[]')

    await wrapper.get('[data-testid="import"]').trigger('click')

    expect(document.body.textContent, 'template in the dialog').toContain('linked to the template griefing')

    wrapper.unmount()
  })

  it('offers neither preview nor import before there is a list', async () => {
    const { wrapper } = await setup().mount()

    expect(wrapper.get('[data-testid="preview"]').attributes('disabled'), 'preview').toBeDefined()
    expect(wrapper.get('[data-testid="import"]').attributes('disabled'), 'import').toBeDefined()

    wrapper.unmount()
  })

  it('previews pasted text as a dry run without asking for confirmation', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await pickTemplate(wrapper)
    await paste(wrapper, '[{"uuid":"x"}]')

    await wrapper.get('[data-testid="preview"]').trigger('click')
    await flushPromises()

    const request = api.importVanillaBans.mock.calls[0]![0] as { dryRun: boolean; players: Blob }
    expect(request.dryRun, 'dry run').toBe(true)
    expect(await readText(request.players), 'the pasted text is sent').toBe('[{"uuid":"x"}]')
    expect(wrapper.find('[role="dialog"]').exists(), 'no dialog').toBe(false)
    expect(wrapper.get('[data-testid="result"]').text(), 'marked as a preview').toContain('nothing was changed')
    expect(wrapper.get('[data-testid="imported"]').text()).toBe('3')

    wrapper.unmount()
  })

  it('asks for confirmation before importing and sends nothing until it is given', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await pickTemplate(wrapper)
    await paste(wrapper, '[]')

    await wrapper.get('[data-testid="import"]').trigger('click')

    expect(wrapper.find('[role="dialog"]').exists(), 'dialog').toBe(true)
    expect(api.importVanillaBans, 'nothing sent').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('imports for real after the confirmation and shows imported, skipped and errors', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await pickTemplate(wrapper)
    await paste(wrapper, '[]')

    await wrapper.get('[data-testid="import"]').trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect((api.importVanillaBans.mock.calls[0]![0] as { dryRun: boolean }).dryRun, 'real run').toBe(false)
    expect(wrapper.get('[data-testid="imported"]').text(), 'imported').toBe('3')
    expect(wrapper.get('[data-testid="skipped"]').text(), 'skipped').toBe('1')
    expect(wrapper.get('[data-testid="errors"]').text(), 'errors').toBe('1')
    expect(wrapper.get('[data-testid="invalid-entries"]').text(), 'description of the invalid entry').toContain('bad uuid')
    expect(wrapper.get('[data-testid="ips"]').text(), 'ip bans are counted').toContain('2 found, 2 skipped')
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog closed').toBe(false)

    wrapper.unmount()
  })

  it('announces the outcome in the always-present live region', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await pickTemplate(wrapper)
    const region = wrapper.get('[data-testid="announcement"]')
    expect(region.attributes('aria-live'), 'live region').toBe('polite')
    await paste(wrapper, '[]')

    await wrapper.get('[data-testid="preview"]').trigger('click')
    await flushPromises()

    expect(region.text()).toBe('Preview finished: 3 would be imported, 1 skipped, 1 with errors.')

    wrapper.unmount()
  })

  it('shows the backend message as an alert when the file cannot be read', async () => {
    const { mount } = setup({ importVanillaBans: vi.fn(async () => fail('Failed to read uploaded file(s): not an array', 400)) })
    const { wrapper } = await mount()
    await pickTemplate(wrapper)
    await paste(wrapper, 'nope')

    await wrapper.get('[data-testid="preview"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('Failed to read uploaded file(s): not an array')
    expect(wrapper.find('[data-testid="result"]').exists(), 'no result').toBe(false)

    wrapper.unmount()
  })

  it('explains a 413 instead of showing a bare status', async () => {
    const { mount } = setup({ importVanillaBans: vi.fn(async () => fail('HTTP 413', 413, 'UNKNOWN')) })
    const { wrapper } = await mount()
    await pickTemplate(wrapper)
    await paste(wrapper, '[]')

    await wrapper.get('[data-testid="preview"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('larger than 1 MiB')

    wrapper.unmount()
  })

  it('refuses an oversized list without sending it', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await pickTemplate(wrapper)
    await paste(wrapper, 'x'.repeat(1024 * 1024 + 1))

    await wrapper.get('[data-testid="preview"]').trigger('click')
    await flushPromises()

    expect(api.importVanillaBans, 'nothing sent').not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('larger than 1 MiB')

    wrapper.unmount()
  })

  it('labels every input', async () => {
    const { wrapper } = await setup().mount()

    for (const id of ['players-file', 'paste', 'ips-file']) {
      const input = wrapper.get(`[data-testid="${id}"]`)
      expect(wrapper.find(`label[for="${input.attributes('id')}"]`).exists(), `label of ${id}`).toBe(true)
    }

    wrapper.unmount()
  })
})
