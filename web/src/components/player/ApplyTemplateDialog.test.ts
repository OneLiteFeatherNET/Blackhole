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

// The administration workspace is ADMIN only, but the moderation dialogs still offer the templates to a moderator.
describe('ApplyTemplateDialog for a moderator', () => {
  it('still lists the templates and applies one, with no administration access', async () => {
    const { api } = setup()
    const { wrapper } = await mountWithApp(ApplyTemplateDialog, { api: mockApi(api), props: { open: true, owner: OWNER }, roles: ['MODERATOR', 'VIEWER'] })

    const options = (await listOptions(wrapper.get('[role="combobox"]'))).map((o) => o.label)
    expect(options, 'templates offered to a moderator').toEqual(['hacking', 'spam'])
    expect(api.templates, 'the templates list is read').toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('ApplyTemplateDialog', () => {
  it('lists the templates and keeps Apply disabled until one is chosen', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const options = (await listOptions(wrapper.get('[role="combobox"]'))).map((o) => o.label)
    expect(options).toEqual(['hacking', 'spam'])
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

describe('ApplyTemplateDialog for a report', () => {
  const sections = [
    { key: 'network', enforcement: 'BAN' },
    { key: 'chat', enforcement: 'MUTE' },
  ]

  function reportSetup(category: string) {
    const api = {
      templates: vi.fn(async () => ok(templates)),
      sections: vi.fn(async () => ok(sections)),
      previewApply: vi.fn(async () => ok(preview())),
      applyTemplate: vi.fn(),
      resolveReport: vi.fn(async () => ok({})),
    }
    const report = { identifier: 'r-1', reportedHash: OWNER, reporterHash: 'cd'.repeat(64), category, createdAt: 0, updatedAt: 0, metaData: {} }
    return { api, mount: () => mountWithApp(ApplyTemplateDialog, { api: mockApi(api), props: { open: true, owner: OWNER, report } }) }
  }

  it('offers only templates of sections that fit a cheating report, until asked for all', async () => {
    const { mount } = reportSetup('CHEATING')
    const { wrapper } = await mount()

    expect((await listOptions(wrapper.get('[role="combobox"]'))).map((option) => option.value)).toEqual(['hacking'])

    await wrapper.get('[data-testid="show-all-templates"] input').setValue(true)
    expect((await listOptions(wrapper.get('[role="combobox"]'))).map((option) => option.value)).toEqual(['hacking', 'spam'])
    wrapper.unmount()
  })

  it('offers mute templates for chat abuse', async () => {
    const { mount } = reportSetup('CHAT_ABUSE')
    const { wrapper } = await mount()

    expect((await listOptions(wrapper.get('[role="combobox"]'))).map((option) => option.value)).toEqual(['spam'])
    wrapper.unmount()
  })

  it('offers every template for the category other, without a filter switch', async () => {
    const { mount } = reportSetup('OTHER')
    const { wrapper } = await mount()

    expect((await listOptions(wrapper.get('[role="combobox"]'))).map((option) => option.value)).toEqual(['hacking', 'spam'])
    expect(wrapper.find('[data-testid="show-all-templates"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('resolves the report with the template instead of applying it on its own', async () => {
    const { api, mount } = reportSetup('CHEATING')
    const { wrapper } = await mount()

    await choose(wrapper, 'hacking')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.resolveReport).toHaveBeenCalledWith('r-1', { status: 'ACTIONED', templateId: 'id-1' })
    expect(api.applyTemplate).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeTruthy()
    wrapper.unmount()
  })
})
