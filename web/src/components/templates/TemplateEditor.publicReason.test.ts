import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { Section, Template } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import TemplateEditor from './TemplateEditor.vue'

const ADMIN = ['ADMIN', 'MODERATOR', 'VIEWER']
const MODERATOR = ['MODERATOR', 'VIEWER']

const network = { key: 'network', displayName: 'Network', enabled: true } as Section

function template(overrides: Partial<Template> = {}): Template {
  return {
    identifier: 'id-1',
    key: 'hacking',
    reason: 'Hacking',
    section: 'network',
    escalationGroup: null,
    escalationWindow: null,
    warnWindow: null,
    publicReason: null,
    steps: [{ kind: 'PUNISH', duration: 'P1D', eloCost: 50 }],
    ...overrides,
  } as Template
}

function setup(overrides: Record<string, unknown> = {}, roles: string[] = ADMIN, existing: Template = template()) {
  const api = {
    updateTemplate: vi.fn(async () => ok(existing)),
    createTemplate: vi.fn(async () => ok(existing)),
    ...overrides,
  }
  return {
    api,
    mount: () =>
      mountWithApp(TemplateEditor, {
        api: mockApi(api),
        roles,
        props: { open: true, template: existing, sections: [network] },
      }),
  }
}

/** The text field whose label starts with the given text, found through its label's `for` attribute. */
function field(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper'], label: string) {
  const found = wrapper.findAll('[role="dialog"] label').find((candidate) => candidate.text().startsWith(label))
  if (!found) throw new Error(`no field labelled ${label}`)
  return wrapper.get(`[role="dialog"] [id="${found.attributes('for')}"]`)
}

describe('TemplateEditor: public reason', () => {
  it('shows the public reason field with its counter to an admin', async () => {
    const { mount } = setup({}, ADMIN)
    const { wrapper } = await mount()

    expect(wrapper.findAll('[role="dialog"] label').some((label) => label.text().startsWith('Public reason')), 'the admin edits the public reason').toBe(true)
    expect(wrapper.get('[role="dialog"]').text()).toContain('0 / 200')

    wrapper.unmount()
  })

  it('does not show the public reason field to a moderator', async () => {
    const { mount } = setup({}, MODERATOR)
    const { wrapper } = await mount()

    expect(wrapper.findAll('[role="dialog"] label').some((label) => label.text().startsWith('Public reason'))).toBe(false)

    wrapper.unmount()
  })

  it('saves the edited public reason with the template', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await field(wrapper, 'Public reason').setValue('Cheating is not tolerated here')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateTemplate).toHaveBeenCalledWith('id-1', expect.objectContaining({ publicReason: 'Cheating is not tolerated here' }))

    wrapper.unmount()
  })

  it('keeps the existing public reason when a moderator saves the template', async () => {
    const { api, mount } = setup({}, MODERATOR, template({ publicReason: 'Kept as written by an admin' }))
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateTemplate).toHaveBeenCalledWith('id-1', expect.objectContaining({ publicReason: 'Kept as written by an admin' }))

    wrapper.unmount()
  })

  it('refuses a public reason longer than 200 characters before sending it', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await field(wrapper, 'Public reason').setValue('a'.repeat(201))
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(api.updateTemplate, 'the backend would refuse it too, so it is not sent').not.toHaveBeenCalled()
    expect(field(wrapper, 'Public reason').attributes('aria-invalid'), 'the message sits next to the field').toBe('true')

    wrapper.unmount()
  })

  it('shows a refused public reason next to its field', async () => {
    const updateTemplate = vi.fn(async () => fail('publicReason must not contain an IP address'))
    const { mount } = setup({ updateTemplate })
    const { wrapper } = await mount()

    await field(wrapper, 'Public reason').setValue('1.2.3.4 is the attacker')
    await wrapper.get('[data-testid="save"]').trigger('click')
    await flushPromises()

    expect(field(wrapper, 'Public reason').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[role="dialog"]').text()).toContain('publicReason must not contain an IP address')

    wrapper.unmount()
  })
})
