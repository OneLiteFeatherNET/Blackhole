import { flushPromises } from '@vue/test-utils'
import { chooseOption, listOptions } from '../../test/select'
import { describe, expect, it, vi } from 'vitest'
import type { ApplyPreview, ApplyResult, Template } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import ApplyTemplateDialog from './ApplyTemplateDialog.vue'

const OWNER = 'ab'.repeat(64)

const templates = [
  { identifier: 'id-1', key: 'hacking', reason: 'Hacking', section: 'network', steps: [] },
  { identifier: 'id-2', key: 'spam', reason: 'Spam', section: 'chat', steps: [] },
] as unknown as Template[]

function preview(overrides: Partial<ApplyPreview> = {}): ApplyPreview {
  return {
    section: 'network',
    enforcement: 'BAN',
    step: 2,
    stepCount: 3,
    duration: 'P7D',
    eloCost: 150,
    scoreBefore: 900,
    scoreAfter: 750,
    permanentThreshold: 300,
    escalatesToPermanent: false,
    ...overrides,
  }
}

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    templates: vi.fn(async () => ok(templates)),
    previewApply: vi.fn(async () => ok(preview())),
    applyTemplate: vi.fn(async () => ok({ step: 2, stepCount: 3, scoreAfter: 750, section: 'network' } as unknown as ApplyResult)),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(ApplyTemplateDialog, { api: mockApi(api), props: { open: true, owner: OWNER } }) }
}

async function choose(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper'], key: string) {
  await chooseOption(wrapper.get('[role="combobox"]'), key)
  await flushPromises()
}

describe('ApplyTemplateDialog', () => {
  it('lists the templates and keeps Apply disabled until one is chosen', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const options = (await listOptions(wrapper.get('[role="combobox"]'))).map((o) => o.label)
    expect(options).toEqual(['Choose a template', 'hacking', 'spam'])
    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled'), 'nothing chosen yet').toBeDefined()

    wrapper.unmount()
  })

  it('previews the step that will be applied before anything is applied', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await choose(wrapper, 'hacking')

    expect(api.previewApply).toHaveBeenCalledWith(OWNER, 'hacking')
    expect(wrapper.get('[data-testid="preview"]').text()).toContain('Escalation step 2 of 3')
    expect(wrapper.get('[data-testid="preview"]').text()).toContain('1 week, costs 150 ELO')
    expect(wrapper.get('[data-testid="preview"]').text()).toContain('Score in network: 900 to 750')
    expect(wrapper.find('[data-testid="permanent-warning"]').exists(), 'no warning below the threshold').toBe(false)
    expect(api.applyTemplate).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('warns that the player will be punished permanently when the cost crosses the permanent threshold', async () => {
    const crossing = preview({ scoreBefore: 400, scoreAfter: 250, escalatesToPermanent: true })
    const { api, mount } = setup({ previewApply: vi.fn(async () => ok(crossing)) })
    const { wrapper } = await mount()

    await choose(wrapper, 'hacking')

    const warning = wrapper.get('[data-testid="permanent-warning"]')
    expect(warning.attributes('role')).toBe('alert')
    expect(warning.text()).toContain('permanently in network')
    expect(warning.text()).toContain('250')
    expect(warning.text()).toContain('300')
    expect(api.applyTemplate, 'the warning comes before anything is applied').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('applies the chosen template on confirm and reports the outcome', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await choose(wrapper, 'hacking')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.applyTemplate).toHaveBeenCalledWith(OWNER, 'hacking')
    expect(wrapper.emitted('done')).toEqual([['Applied step 2 of 3; the score is now 750.']])

    wrapper.unmount()
  })

  it('says so when the application escalated to a permanent punishment', async () => {
    const escalated = { step: 3, stepCount: 3, scoreAfter: 250, section: 'network', escalation: { resultingScore: 250 } } as unknown as ApplyResult
    const { mount } = setup({ applyTemplate: vi.fn(async () => ok(escalated)) })
    const { wrapper } = await mount()
    await choose(wrapper, 'hacking')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('done')).toEqual([['Applied; the player is now permanently punished in network.']])

    wrapper.unmount()
  })

  it('keeps the dialog open and shows the backend error when applying fails', async () => {
    const { mount } = setup({ applyTemplate: vi.fn(async () => fail('already permanently punished', 409, 'CONFLICT')) })
    const { wrapper } = await mount()
    await choose(wrapper, 'hacking')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('already permanently punished')
    expect(wrapper.emitted('done')).toBeUndefined()

    wrapper.unmount()
  })

  it('shows why no preview is available and keeps Apply disabled', async () => {
    const { mount } = setup({ previewApply: vi.fn(async () => fail('section is disabled', 409, 'CONFLICT')) })
    const { wrapper } = await mount()

    await choose(wrapper, 'hacking')

    expect(wrapper.get('[data-testid="error"]').text()).toContain('section is disabled')
    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('only loads the templates once it is opened', async () => {
    const { api } = setup()
    const { wrapper } = await mountWithApp(ApplyTemplateDialog, { api: mockApi(api), props: { open: false, owner: OWNER } })

    expect(api.templates).not.toHaveBeenCalled()
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(api.templates).toHaveBeenCalledOnce()

    wrapper.unmount()
  })

  it('emits close on cancel', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="cancel"]').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(1)

    wrapper.unmount()
  })
})
