import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { Section, Template } from '../api/types'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import TemplatesView from './TemplatesView.vue'

function template(overrides: Partial<Template> = {}): Template {
  return {
    identifier: 'id-hacking',
    key: 'hacking',
    reason: 'Hacking',
    section: 'network',
    escalationGroup: 'cheating',
    escalationWindow: 'P90D',
    steps: [
      { duration: 'P1D', eloCost: 100 },
      { duration: 'P7D', eloCost: 150 },
    ],
    metaData: {},
    ...overrides,
  } as Template
}

const sections = [
  { key: 'chat', displayName: 'Chat', enforcement: 'MUTE', enabled: true },
  { key: 'network', displayName: 'Network', enforcement: 'BAN', enabled: true },
  { key: 'old', displayName: 'Old', enforcement: 'BAN', enabled: false },
] as unknown as Section[]

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    templates: vi.fn(async () => ok([template()])),
    sections: vi.fn(async () => ok(sections)),
    createTemplate: vi.fn(async () => ok(template())),
    updateTemplate: vi.fn(async () => ok(template())),
    deleteTemplate: vi.fn(async () => ok(template())),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(TemplatesView, { api: mockApi(api), path: '/templates' }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

const dialogInput = (wrapper: Wrapper, label: string) => {
  const field = wrapper.findAll('[role="dialog"] label').find((l) => l.text().startsWith(label))
  if (!field) throw new Error(`no field labelled ${label}`)
  return wrapper.get(`[role="dialog"] [id="${field.attributes("for")}"]`)
}

describe('TemplatesView: list', () => {
  it('lists the templates with section, group and steps', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const row = wrapper.get('tbody tr')
    expect(row.text()).toContain('hacking')
    expect(row.text()).toContain('network')
    expect(row.text()).toContain('cheating')
    expect(row.text()).toContain('2 steps')
    expect(row.text()).toContain('1 day / 100')
    expect(row.text()).toContain('7 days / 150')

    wrapper.unmount()
  })

  it('shows an empty message without templates', async () => {
    const { mount } = setup({ templates: vi.fn(async () => ok([])) })
    const { wrapper } = await mount()

    expect(wrapper.text()).toContain('There are no templates yet.')
    expect(wrapper.find('table').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the error with a retry when loading fails', async () => {
    const templates = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok([template()]))
    const { mount } = setup({ templates })
    const { wrapper } = await mount()

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('tbody tr').exists()).toBe(true)

    wrapper.unmount()
  })
})

describe('TemplatesView: create', () => {
  it('creates a template and lists it afterwards', async () => {
    const created = template({ identifier: 'id-spam', key: 'spam', reason: 'Spam', section: 'chat', escalationGroup: '', escalationWindow: null, steps: [{ duration: 'PT1H', eloCost: 0 }] })
    const templates = vi.fn().mockResolvedValueOnce(ok([])).mockResolvedValueOnce(ok([created]))
    const { api, mount } = setup({ templates })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-template"]').trigger('click')
    await dialogInput(wrapper, 'Key').setValue('spam')
    await dialogInput(wrapper, 'Reason').setValue('Spam')
    await wrapper.get('[role="dialog"] select').setValue('chat')
    await dialogInput(wrapper, 'Duration (step 1)').setValue('PT1H')
    await dialogInput(wrapper, 'ELO cost (step 1)').setValue('0')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createTemplate).toHaveBeenCalledWith({
      key: 'spam',
      reason: 'Spam',
      section: 'chat',
      escalationGroup: null,
      escalationWindow: null,
      steps: [{ duration: 'PT1H', eloCost: 0 }],
    })
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog closed after saving').toBe(false)
    expect(wrapper.get('tbody tr').text()).toContain('spam')
    expect(wrapper.get('[role="status"]').text()).toContain('Template saved.')

    wrapper.unmount()
  })

  it('offers only enabled sections', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-template"]').trigger('click')

    expect(wrapper.findAll('[role="dialog"] option').map((o) => o.attributes('value'))).toEqual(['chat', 'network'])

    wrapper.unmount()
  })

  it('shows the validation error next to the step duration and saves nothing', async () => {
    const createTemplate = vi.fn(async () => fail('step 1 needs a duration that is a positive ISO-8601 duration', 400, 'VALIDATION'))
    const templates = vi.fn(async () => ok([] as Template[]))
    const { mount } = setup({ createTemplate, templates })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="new-template"]').trigger('click')
    await dialogInput(wrapper, 'Key').setValue('spam')
    await dialogInput(wrapper, 'Reason').setValue('Spam')
    await dialogInput(wrapper, 'Duration (step 1)').setValue('soon')
    await dialogInput(wrapper, 'ELO cost (step 1)').setValue('10')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    const duration = dialogInput(wrapper, 'Duration (step 1)')
    expect(duration.attributes('aria-invalid')).toBe('true')
    const message = wrapper.get(`[id="${duration.attributes('aria-describedby')}"]`)
    expect(message.text()).toContain('step 1 needs a duration')
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog stays open').toBe(true)
    expect(templates, 'the list was not reloaded').toHaveBeenCalledTimes(1)

    await duration.setValue('P1D')
    expect(dialogInput(wrapper, 'Duration (step 1)').attributes('aria-invalid'), 'the message goes once the field is edited').toBeUndefined()

    wrapper.unmount()
  })

  it('shows messages for the key and for general problems', async () => {
    const createTemplate = vi.fn(async () => fail("a template with key 'spam' already exists", 409, 'CONFLICT'))
    const { mount } = setup({ createTemplate })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="new-template"]').trigger('click')
    await dialogInput(wrapper, 'Key').setValue('spam')
    await dialogInput(wrapper, 'Reason').setValue('Spam')
    await dialogInput(wrapper, 'Duration (step 1)').setValue('PT1H')
    await dialogInput(wrapper, 'ELO cost (step 1)').setValue('0')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    const key = dialogInput(wrapper, 'Key')
    expect(wrapper.get(`[id="${key.attributes('aria-describedby')}"]`).text()).toContain('already exists')

    wrapper.unmount()
  })

  it('rejects a cost that is not a whole number without calling the API', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="new-template"]').trigger('click')
    await dialogInput(wrapper, 'Key').setValue('spam')
    await dialogInput(wrapper, 'Reason').setValue('Spam')
    await dialogInput(wrapper, 'Duration (step 1)').setValue('PT1H')
    await dialogInput(wrapper, 'ELO cost (step 1)').setValue('-5')

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Enter a whole number of 0 or more.')
    expect(api.createTemplate).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('TemplatesView: edit', () => {
  it('adds a third step and shows three steps after saving', async () => {
    const updated = template({ steps: [...template().steps, { duration: 'P30D', eloCost: 250 }] })
    const templates = vi.fn().mockResolvedValueOnce(ok([template()])).mockResolvedValueOnce(ok([updated]))
    const { api, mount } = setup({ templates })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')
    expect(dialogInput(wrapper, 'Key').element).toHaveProperty('value', 'hacking')
    expect(dialogInput(wrapper, 'Duration (step 2)').element).toHaveProperty('value', 'P7D')
    await wrapper.get('[data-testid="add-step"]').trigger('click')
    await dialogInput(wrapper, 'Duration (step 3)').setValue('P30D')
    await dialogInput(wrapper, 'ELO cost (step 3)').setValue('250')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateTemplate).toHaveBeenCalledWith('id-hacking', {
      key: 'hacking',
      reason: 'Hacking',
      section: 'network',
      escalationGroup: 'cheating',
      escalationWindow: 'P90D',
      steps: [
        { duration: 'P1D', eloCost: 100 },
        { duration: 'P7D', eloCost: 150 },
        { duration: 'P30D', eloCost: 250 },
      ],
    })
    expect(wrapper.get('tbody tr').text()).toContain('3 steps')

    wrapper.unmount()
  })

  it('reorders and removes steps', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')

    await wrapper.get('[aria-label="Move step 2 up"]').trigger('click')
    expect(dialogInput(wrapper, 'Duration (step 1)').element).toHaveProperty('value', 'P7D')
    expect(dialogInput(wrapper, 'Duration (step 2)').element).toHaveProperty('value', 'P1D')

    await wrapper.get('[aria-label="Remove step 1"]').trigger('click')
    expect(wrapper.findAll('[role="dialog"] label').filter((l) => l.text().startsWith('Duration'))).toHaveLength(1)

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateTemplate).toHaveBeenCalledWith('id-hacking', expect.objectContaining({ steps: [{ duration: 'P1D', eloCost: 100 }] }))

    wrapper.unmount()
  })

  it('does not offer to move the first step up or the last step down', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')

    expect(wrapper.get('[aria-label="Move step 1 up"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[aria-label="Move step 2 down"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('discards the edits when the dialog is cancelled', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')
    await dialogInput(wrapper, 'Reason').setValue('changed')

    await wrapper.get('[data-testid="cancel"]').trigger('click')
    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')

    expect(dialogInput(wrapper, 'Reason').element).toHaveProperty('value', 'Hacking')
    expect(api.updateTemplate).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('TemplatesView: delete', () => {
  it('deletes after confirmation and reloads the list', async () => {
    const templates = vi.fn().mockResolvedValueOnce(ok([template()])).mockResolvedValueOnce(ok([]))
    const { api, mount } = setup({ templates })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="delete-hacking"]').trigger('click')
    expect(api.deleteTemplate, 'asks first').not.toHaveBeenCalled()
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.deleteTemplate).toHaveBeenCalledWith('id-hacking')
    expect(wrapper.find('tbody tr').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('Template deleted.')

    wrapper.unmount()
  })

  it('shows the conflict instead of failing silently when the template is still referenced', async () => {
    const deleteTemplate = vi.fn(async () =>
      fail('at least one punishment still references this template', 409, 'CONFLICT'),
    )
    const { mount } = setup({ deleteTemplate })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="delete-hacking"]').trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('The template is still in use')
    expect(wrapper.get('[data-testid="error"]').text()).toContain('at least one punishment still references this template')
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog stays open').toBe(true)
    expect(wrapper.find('tbody tr').exists(), 'the template is still listed').toBe(true)

    wrapper.unmount()
  })
})
