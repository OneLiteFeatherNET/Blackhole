import { flushPromises } from '@vue/test-utils'
import { chooseOption, listOptions } from '../test/select'
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

/** Chooses an item of the row menu, which holds the actions beyond edit. */
async function rowAction(wrapper: Wrapper, label: string): Promise<void> {
  await wrapper.get('tbody tr button[aria-haspopup="menu"]').trigger('click')
  const item = wrapper.findAll('[role="menuitem"]').find((candidate) => candidate.text() === label)
  if (!item) throw new Error(`no menu item ${label}`)
  await item.trigger('click')
}

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
    expect(row.text()).toContain('1 week / 150')

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
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'chat')
    await dialogInput(wrapper, 'Duration (step 1)').setValue('1h')
    await dialogInput(wrapper, 'ELO cost (step 1)').setValue('0')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createTemplate).toHaveBeenCalledWith({
      key: 'spam',
      reason: 'Spam',
      section: 'chat',
      escalationGroup: null,
      escalationWindow: null,
      warnWindow: null,
      steps: [{ kind: 'PUNISH', duration: 'PT1H', eloCost: 0 }],
    })
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog closed after saving').toBe(false)
    expect(wrapper.get('tbody tr').text()).toContain('spam')
    expect(wrapper.get('[role="status"]').text()).toContain('Template saved.')

    wrapper.unmount()
  })

  it('lists a disabled section as unavailable instead of hiding it', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-template"]').trigger('click')

    const options = await listOptions(wrapper.get('[role="dialog"] [role="combobox"]'))
    expect(options.map((o) => [o.value, o.disabled])).toEqual([['chat', false], ['network', false], ['old', true]])

    wrapper.unmount()
  })

  it('shows an unreadable duration next to the step and saves nothing', async () => {
    const templates = vi.fn(async () => ok([] as Template[]))
    const { api, mount } = setup({ templates })
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
    expect(message.text()).toContain('Not a duration')
    expect(api.createTemplate).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog stays open').toBe(true)
    expect(templates, 'the list was not reloaded').toHaveBeenCalledTimes(1)

    await duration.setValue('1d')
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
    await dialogInput(wrapper, 'Duration (step 1)').setValue('1h')
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
    await dialogInput(wrapper, 'Duration (step 1)').setValue('1h')
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
    expect(dialogInput(wrapper, 'Duration (step 2)').element).toHaveProperty('value', '1w')
    await wrapper.get('[data-testid="add-step"]').trigger('click')
    await dialogInput(wrapper, 'Duration (step 3)').setValue('30d')
    await dialogInput(wrapper, 'ELO cost (step 3)').setValue('250')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateTemplate).toHaveBeenCalledWith('id-hacking', {
      key: 'hacking',
      reason: 'Hacking',
      section: 'network',
      escalationGroup: 'cheating',
      escalationWindow: 'P90D',
      warnWindow: null,
      steps: [
        { kind: 'PUNISH', duration: 'P1D', eloCost: 100 },
        { kind: 'PUNISH', duration: 'P7D', eloCost: 150 },
        { kind: 'PUNISH', duration: 'P30D', eloCost: 250 },
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
    expect(dialogInput(wrapper, 'Duration (step 1)').element).toHaveProperty('value', '1w')
    expect(dialogInput(wrapper, 'Duration (step 2)').element).toHaveProperty('value', '1d')

    await wrapper.get('[aria-label="Remove step 1"]').trigger('click')
    expect(wrapper.findAll('[role="dialog"] label').filter((l) => l.text().startsWith('Duration'))).toHaveLength(1)

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateTemplate).toHaveBeenCalledWith('id-hacking', expect.objectContaining({ steps: [{ kind: 'PUNISH', duration: 'P1D', eloCost: 100 }] }))

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

    await rowAction(wrapper, 'Delete')
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

    await rowAction(wrapper, 'Delete')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('The template is still in use')
    expect(wrapper.get('[data-testid="error"]').text()).toContain('at least one punishment still references this template')
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog stays open').toBe(true)
    expect(wrapper.find('tbody tr').exists(), 'the template is still listed').toBe(true)

    wrapper.unmount()
  })
})

describe('TemplatesView: editor structure', () => {
  it('summarises the steps in words', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')

    expect(wrapper.get('[data-testid="summary"]').text()).toContain('Offence 1: 1 day, −100 ELO')
    expect(wrapper.get('[data-testid="summary"]').text()).toContain('Offence 2: 1 week, −150 ELO')

    wrapper.unmount()
  })

  it('keeps the escalation settings collapsed for a new template until asked', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-template"]').trigger('click')
    expect(wrapper.get('[data-testid="advanced-toggle"]').attributes('aria-expanded')).toBe('false')

    await wrapper.get('[data-testid="advanced-toggle"]').trigger('click')
    expect(wrapper.get('[data-testid="advanced-toggle"]').attributes('aria-expanded')).toBe('true')

    wrapper.unmount()
  })

  it('opens the advanced section for a template that has an escalation group', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')

    expect(wrapper.get('[data-testid="advanced-toggle"]').attributes('aria-expanded')).toBe('true')
    expect(dialogInput(wrapper, 'Escalation window').element).toHaveProperty('value', '90d')

    wrapper.unmount()
  })

  it('sends the escalation window as ISO-8601 when it was typed in the short form', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')

    await dialogInput(wrapper, 'Escalation window').setValue('2w')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateTemplate).toHaveBeenCalledWith('id-hacking', expect.objectContaining({ escalationWindow: 'P14D' }))

    wrapper.unmount()
  })
})

describe('TemplatesView: page template', () => {
  it('titles the page Templates with the one level-one heading', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.findAll('h1').map((heading) => heading.text())).toEqual(['Templates'])

    wrapper.unmount()
  })

  it('names the ELO unit of every step cost', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const row = wrapper.get('tbody tr')
    expect(row.text()).toContain('1 day / 100 ELO')
    expect(row.text()).toContain('1 week / 150 ELO')

    wrapper.unmount()
  })

  it('keeps edit in the row and moves delete into the row menu', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    const row = wrapper.get('tbody tr')

    expect(row.find('[data-testid="edit-hacking"]').exists(), 'edit stays in the row').toBe(true)
    expect(row.find('[data-testid="delete-hacking"]').exists(), 'delete is not a row button').toBe(false)
    await row.get('button[aria-haspopup="menu"]').trigger('click')
    expect(wrapper.findAll('[role="menuitem"]').map((item) => item.text())).toEqual(['Delete'])

    wrapper.unmount()
  })

  it('confirms the delete with the error button', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('tbody tr button[aria-haspopup="menu"]').trigger('click')
    await wrapper.get('[role="menuitem"]').trigger('click')

    expect(wrapper.get('[data-testid="confirm"]').classes(), 'destructive confirm').toContain('bg-error')

    wrapper.unmount()
  })

  it('shows the empty state with its create action for an empty list', async () => {
    const { mount } = setup({ templates: vi.fn(async () => ok([])) })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-variant="no-items"]').text()).toContain('There are no templates yet.')

    wrapper.unmount()
  })
})

/** The kind select of step `index` (zero-based): the first combobox of the dialog is the section. */
const stepKind = (wrapper: Wrapper, index: number) => wrapper.findAll('[role="dialog"] [role="combobox"]')[index + 1]!

const hasDurationField = (wrapper: Wrapper, step: number) =>
  wrapper.findAll('[role="dialog"] label').some((label) => label.text().startsWith(`Duration (step ${step})`))

describe('TemplatesView: warning and kick steps', () => {
  it('creates a warning step without a duration', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-template"]').trigger('click')
    await dialogInput(wrapper, 'Key').setValue('warn-spam')
    await dialogInput(wrapper, 'Reason').setValue('Spam')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'chat')
    await chooseOption(stepKind(wrapper, 0), 'WARN')
    await dialogInput(wrapper, 'ELO cost (step 1)').setValue('0')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.createTemplate, 'the warning step is saved').toHaveBeenCalledWith(
      expect.objectContaining({ steps: [{ kind: 'WARN', duration: null, eloCost: 0 }] }),
    )
    wrapper.unmount()
  })

  it('asks for no duration once a step is a kick', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-template"]').trigger('click')
    expect(hasDurationField(wrapper, 1), 'a punishment step asks for a duration').toBe(true)
    await chooseOption(stepKind(wrapper, 0), 'KICK')

    expect(hasDurationField(wrapper, 1), 'a kick step asks for no duration').toBe(false)
    wrapper.unmount()
  })

  it('shows a warning step with its kind in the list instead of an empty duration', async () => {
    const { mount } = setup({ templates: vi.fn(async () => ok([template({ steps: [{ kind: 'WARN', duration: null, eloCost: 0 }] } as never)])) })
    const { wrapper } = await mount()

    expect(wrapper.get('tbody tr').text()).toContain('1. Warning / 0 ELO')
    wrapper.unmount()
  })

  it('opens an existing warning step without a duration field and summarises it in words', async () => {
    const { mount } = setup({ templates: vi.fn(async () => ok([template({ steps: [{ kind: 'WARN', duration: null, eloCost: 25 }] } as never)])) })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')

    expect(hasDurationField(wrapper, 1), 'no duration for a warning').toBe(false)
    expect(wrapper.get('[data-testid="summary"]').text()).toContain('Offence 1: warning, −25 ELO')
    wrapper.unmount()
  })

  it('sends the warn window as ISO-8601 with the template', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="edit-hacking"]').trigger('click')

    await dialogInput(wrapper, 'Warning window').setValue('30d')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateTemplate).toHaveBeenCalledWith('id-hacking', expect.objectContaining({ warnWindow: 'P30D' }))
    wrapper.unmount()
  })

  it('shows the backend message for a warning step that still has a duration, on its kind', async () => {
    const message = 'step 1 is a WARN step and must not have a duration'
    const { mount } = setup({ createTemplate: vi.fn(async () => fail(message, 400)) })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-template"]').trigger('click')
    await dialogInput(wrapper, 'Key').setValue('warn-spam')
    await dialogInput(wrapper, 'Reason').setValue('Spam')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'chat')
    await chooseOption(stepKind(wrapper, 0), 'WARN')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="dialog"]').text()).toContain(message)
    wrapper.unmount()
  })
})
