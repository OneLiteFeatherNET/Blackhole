import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { Case } from '../../api/types'
import { caseResponse, ME, OTHER } from '../../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption, listOptions } from '../../test/select'
import CaseHeader from './CaseHeader.vue'

function setup(options: { roles?: string[]; kase?: Partial<Case>; updateCase?: unknown } = {}) {
  const updateCase = options.updateCase ?? vi.fn(async () => ok(caseResponse({ version: 4 })))
  return {
    updateCase: updateCase as ReturnType<typeof vi.fn>,
    mount: () =>
      mountWithApp(CaseHeader, {
        api: mockApi({ updateCase }),
        props: { kase: caseResponse(options.kase) },
        roles: options.roles ?? ['ADMIN', 'MODERATOR', 'VIEWER'],
      }),
  }
}

const combobox = (wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper'], id: string) => wrapper.get(`[data-testid="${id}"] [role="combobox"]`)

describe('CaseHeader: facts', () => {
  it('shows the player prefix, section, punishment state and the staff metadata', async () => {
    const { mount } = setup({ kase: { status: 'IN_REVIEW', priority: 'HIGH', tags: ['spam'], assigneeOid: OTHER, assigneeName: 'Ada' } })
    const { wrapper } = await mount()

    expect(wrapper.find('h1, h2').exists(), 'the page title names the case, the header adds no heading').toBe(false)
    expect(wrapper.get('[data-testid="player-prefix"]').text()).toBe('abcdef12…')
    expect(wrapper.get('[data-testid="punishment-state"]').text()).toBe('Active')
    expect(combobox(wrapper, 'status').text()).toContain('In review')
    expect(combobox(wrapper, 'priority').text()).toContain('High')
    expect(combobox(wrapper, 'assignee').text()).toContain('Ada')
    expect(wrapper.get('[data-testid="tags"]').text()).toContain('spam')

    wrapper.unmount()
  })
})

describe('CaseHeader: layout', () => {
  it('lists the facts as a definition list in the order player, section, issued', async () => {
    const { mount } = setup({ kase: { section: 'network' } })
    const { wrapper } = await mount()

    expect(wrapper.findAll('dl dt').map((term) => term.text())).toEqual(['Player', 'Section', 'Issued'])

    wrapper.unmount()
  })
})

describe('CaseHeader: roles', () => {
  it('lets a viewer read but not change anything, and says why', async () => {
    const { mount } = setup({ roles: ['VIEWER'], kase: { tags: ['spam'] } })
    const { wrapper } = await mount()

    for (const id of ['status', 'assignee', 'priority']) expect(combobox(wrapper, id).attributes('disabled'), id).toBeDefined()
    expect(wrapper.find('[data-testid="remove-tag-spam"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="new-tag"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="read-only"]').text()).toContain('moderator role')

    wrapper.unmount()
  })

  it('lets a moderator change the metadata', async () => {
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    for (const id of ['status', 'assignee', 'priority']) expect(combobox(wrapper, id).attributes('disabled'), id).toBeUndefined()
    expect(wrapper.find('[data-testid="read-only"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('CaseHeader: changes', () => {
  it('sends a status change together with the version that was read', async () => {
    const { updateCase, mount } = setup()
    const { wrapper } = await mount()

    await chooseOption(combobox(wrapper, 'status'), 'WAITING')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 3, status: 'WAITING' })
    expect(wrapper.emitted('updated')![0]![0]).toMatchObject({ version: 4 })
    expect(wrapper.emitted('message')).toEqual([['Case saved.']])

    wrapper.unmount()
  })

  it('sends a priority change', async () => {
    const { updateCase, mount } = setup()
    const { wrapper } = await mount()

    await chooseOption(combobox(wrapper, 'priority'), 'URGENT')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 3, priority: 'URGENT' })

    wrapper.unmount()
  })

  it('offers nobody, the signed-in person and the current assignee, and assigns the chosen one', async () => {
    const { updateCase, mount } = setup({ kase: { assigneeOid: OTHER, assigneeName: 'Ada' } })
    const { wrapper } = await mount()

    const options = await listOptions(combobox(wrapper, 'assignee'))
    expect(options.map((option) => option.value)).toEqual(['__none__', ME, OTHER])

    await chooseOption(combobox(wrapper, 'assignee'), ME)
    await flushPromises()
    expect(updateCase).toHaveBeenLastCalledWith('p-0001', { expectedVersion: 3, assigneeOid: ME })

    wrapper.unmount()
  })

  it('removes the assignee with an explicit null', async () => {
    const { updateCase, mount } = setup({ kase: { assigneeOid: OTHER, assigneeName: 'Ada' } })
    const { wrapper } = await mount()

    await chooseOption(combobox(wrapper, 'assignee'), '__none__')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 3, assigneeOid: null })

    wrapper.unmount()
  })

  it('adds a tag to the existing ones and clears the field', async () => {
    const { updateCase, mount } = setup({ kase: { tags: ['spam'] } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-tag"] input').setValue(' Chat-Abuse ')
    await wrapper.get('[data-testid="add-tag"]').trigger('click')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 3, tags: ['spam', 'chat-abuse'] })
    expect((wrapper.get('[data-testid="new-tag"] input').element as HTMLInputElement).value).toBe('')

    wrapper.unmount()
  })

  it.each([
    ['has spaces', 'Lower case letters'],
    ['x'.repeat(33), 'Lower case letters'],
  ])('rejects the tag "%s" without calling the API', async (tag) => {
    const { updateCase, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-tag"] input').setValue(tag)
    await wrapper.get('[data-testid="add-tag"]').trigger('click')
    await flushPromises()

    expect(updateCase).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="new-tag"]').text()).toContain('1 to 32')

    wrapper.unmount()
  })

  it('rejects a tag that is already set and an eleventh tag', async () => {
    const tags = Array.from({ length: 10 }, (_, index) => `t${index}`)
    const { updateCase, mount } = setup({ kase: { tags } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="new-tag"] input').setValue('t1')
    await wrapper.get('[data-testid="add-tag"]').trigger('click')
    expect(wrapper.get('[data-testid="new-tag"]').text()).toContain('already set')

    await wrapper.get('[data-testid="new-tag"] input').setValue('extra')
    await wrapper.get('[data-testid="add-tag"]').trigger('click')
    expect(wrapper.get('[data-testid="new-tag"]').text()).toContain('at most 10')
    expect(updateCase).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('removes a tag by sending the remaining ones', async () => {
    const { updateCase, mount } = setup({ kase: { tags: ['a', 'b'] } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="remove-tag-a"]').trigger('click')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 3, tags: ['b'] })
    expect(wrapper.get('[data-testid="remove-tag-a"]').attributes('aria-label')).toBe('Remove tag a')

    wrapper.unmount()
  })
})

describe('CaseHeader: conflict', () => {
  it('tells the user the case changed, announces it and does not report a save', async () => {
    const { mount } = setup({ updateCase: vi.fn(async () => fail('the case was changed by someone else', 409, 'CONFLICT')) })
    const { wrapper } = await mount()

    await chooseOption(combobox(wrapper, 'status'), 'CLOSED')
    await flushPromises()

    const alert = wrapper.get('[data-testid="conflict"]')
    expect(alert.attributes('role')).toBe('alert')
    expect(alert.text()).toContain('Someone else changed this case')
    expect(wrapper.emitted('updated')).toBeUndefined()
    expect(wrapper.emitted('message')).toBeUndefined()
    expect(combobox(wrapper, 'status').text(), 'the shown value stays the server value').toContain('Open')

    wrapper.unmount()
  })

  it('reloads only when the user asks to, and the notice goes away with the fresh version', async () => {
    const { mount } = setup({ updateCase: vi.fn(async () => fail('stale', 409, 'CONFLICT')) })
    const { wrapper } = await mount()

    await chooseOption(combobox(wrapper, 'status'), 'CLOSED')
    await flushPromises()
    expect(wrapper.emitted('reload')).toBeUndefined()

    await wrapper.get('[data-testid="reload"]').trigger('click')
    expect(wrapper.emitted('reload')).toHaveLength(1)

    await wrapper.setProps({ kase: caseResponse({ version: 5, status: 'WAITING' }) })
    expect(wrapper.find('[data-testid="conflict"]').exists()).toBe(false)
    expect(combobox(wrapper, 'status').text()).toContain('Waiting')

    wrapper.unmount()
  })

  it('shows any other failure with the server message instead of the conflict notice', async () => {
    const { mount } = setup({ updateCase: vi.fn(async () => fail('the assignee is not a known staff member', 422, 'UNKNOWN')) })
    const { wrapper } = await mount()

    await chooseOption(combobox(wrapper, 'priority'), 'LOW')
    await flushPromises()

    expect(wrapper.find('[data-testid="conflict"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="header-error"]').text()).toBe('the assignee is not a known staff member')

    wrapper.unmount()
  })
})
