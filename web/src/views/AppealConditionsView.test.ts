import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MdTextField from '../components/md/MdTextField.vue'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import AppealConditionsView from './AppealConditionsView.vue'

const version = (n: number, text = `text ${n}`) => ({ version: n, text, createdAt: '2030-01-0' + n + 'T10:00:00Z' })

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    appealConditions: vi.fn(async () => ok(version(2))),
    appealConditionVersions: vi.fn(async () => ok([version(2), version(1)])),
    saveAppealConditions: vi.fn(async () => ok(version(3, 'edited'))),
    ...overrides,
  }
  return { api, mount: (roles: string[]) => mountWithApp(AppealConditionsView, { api: mockApi(api), path: '/appeal-conditions', roles }) }
}

describe('AppealConditionsView', () => {
  it('shows the current version and text to a viewer, without an editor', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['VIEWER'])

    expect(wrapper.get('[data-testid="current-version"]').text()).toContain('2')
    expect(wrapper.get('[data-testid="current-text"]').text()).toContain('text 2')
    expect(wrapper.find('textarea').exists(), 'no editor for a viewer').toBe(false)
    expect(wrapper.find('[data-testid="conditions-save"]').exists(), 'no save for a viewer').toBe(false)

    wrapper.unmount()
  })

  it('offers no editor to a moderator either', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    expect(wrapper.find('textarea').exists()).toBe(false)

    wrapper.unmount()
  })

  it('lists the versions newest first', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['VIEWER'])

    const items = wrapper.findAll('[data-testid="version-item"]')
    expect(items.map((item) => item.attributes('data-version'))).toEqual(['2', '1'])

    wrapper.unmount()
  })

  it('preloads the editor of an admin with the current text', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])

    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('text 2')

    wrapper.unmount()
  })

  it('saves the edited text as a new version and shows it as current', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])
    api.appealConditions.mockResolvedValueOnce(ok(version(3, 'edited')))

    await wrapper.get('textarea').setValue('edited')
    await wrapper.get('[data-testid="conditions-save"]').trigger('click')
    await flushPromises()

    expect(api.saveAppealConditions).toHaveBeenCalledWith('edited')
    expect(wrapper.get('[data-testid="current-version"]').text()).toContain('3')

    wrapper.unmount()
  })

  it('disables saving when the text is empty or over 4000 characters and shows the overflow', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])
    const editor = wrapper.get('textarea')
    const save = wrapper.get('[data-testid="conditions-save"]')

    await editor.setValue('   ')
    expect(save.attributes('disabled'), 'empty').toBeDefined()

    await editor.setValue('x'.repeat(4001))
    expect(save.attributes('disabled'), 'too long').toBeDefined()
    expect(wrapper.text(), 'the counter names the overflow').toContain('4001 / 4000')
    expect(api.saveAppealConditions).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('reloads the current version and tells the admin to review when the save conflicts', async () => {
    const { api, mount } = setup({ saveAppealConditions: vi.fn(async () => fail('changed', 409, 'CONFLICT')) })
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])
    api.appealConditions.mockResolvedValueOnce(ok(version(3, 'someone else')))

    await wrapper.get('textarea').setValue('mine')
    await wrapper.get('[data-testid="conditions-save"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="conditions-conflict"]').exists(), 'the conflict is explained').toBe(true)
    expect(wrapper.get('[data-testid="current-version"]').text()).toContain('3')

    wrapper.unmount()
  })

  it('shows another failure message and keeps the typed text', async () => {
    const { mount } = setup({ saveAppealConditions: vi.fn(async () => fail('the text must not be empty', 400)) })
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])

    await wrapper.get('textarea').setValue('mine')
    await wrapper.get('[data-testid="conditions-save"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="conditions-error"]').text()).toContain('the text must not be empty')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('mine')

    wrapper.unmount()
  })
})

describe('AppealConditionsView: page template', () => {
  it('edits the terms in a multi-line MdTextField that carries the counter as its supporting text', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])
    const field = wrapper.findComponent(MdTextField)

    expect(field.props('multiline'), 'a multi-line field').toBe(true)
    expect(field.props('rows'), 'twelve rows').toBe(12)
    expect(field.props('supportingText'), 'the counter under the field').toContain('6 / 4000')

    wrapper.unmount()
  })
})
