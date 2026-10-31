import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ApiResult, Case } from '../../api/types'
import { caseResponse } from '../../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import CaseBar from './CaseBar.vue'

function setup(options: { roles?: string[]; kase?: Partial<Case>; updateCase?: (...args: unknown[]) => Promise<ApiResult<Case>> } = {}) {
  const updateCase = vi.fn(options.updateCase ?? (async () => ok(caseResponse({ version: 4, status: 'CLOSED' }))))
  return {
    updateCase,
    mount: () =>
      mountWithApp(CaseBar, {
        api: mockApi({ updateCase }),
        props: { kase: caseResponse(options.kase) },
        roles: options.roles ?? ['MODERATOR', 'VIEWER'],
      }),
  }
}

describe('CaseBar: state', () => {
  it('shows the case status and the punishment state on the bar', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="case-status"]').text()).toBe('Open')
    expect(wrapper.get('[data-testid="punishment-state"]').text()).toBe('Active')

    wrapper.unmount()
  })

  it('shows the permanent mark next to the punishment state', async () => {
    const { mount } = setup({ kase: { permanent: true } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="case-bar"]').text()).toContain('Permanent')

    wrapper.unmount()
  })
})

describe('CaseBar: closing and reopening', () => {
  it('closes an open case with the version that was read and reports it', async () => {
    const { updateCase, mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="close-case"]').text()).toBe('Close')
    await wrapper.get('[data-testid="close-case"]').trigger('click')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 3, status: 'CLOSED' })
    expect(wrapper.emitted('updated')![0]![0]).toMatchObject({ version: 4 })
    expect(wrapper.emitted('message')).toEqual([['Case closed.']])

    wrapper.unmount()
  })

  it('offers Reopen on a closed case and sets the case back to open', async () => {
    const { updateCase, mount } = setup({ kase: { status: 'CLOSED' }, updateCase: async () => ok(caseResponse({ version: 4, status: 'OPEN' })) })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="close-case"]').exists()).toBe(false)
    await wrapper.get('[data-testid="reopen-case"]').trigger('click')
    await flushPromises()

    expect(updateCase).toHaveBeenCalledWith('p-0001', { expectedVersion: 3, status: 'OPEN' })
    expect(wrapper.emitted('message')).toEqual([['Case reopened.']])

    wrapper.unmount()
  })

  it('sends one change at a time, even on a double click', async () => {
    let finish: (result: ApiResult<Case>) => void = () => undefined
    const { updateCase, mount } = setup({ updateCase: () => new Promise((resolve) => (finish = resolve)) })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="close-case"]').trigger('click')
    await wrapper.get('[data-testid="close-case"]').trigger('click')
    finish(ok(caseResponse({ version: 4, status: 'CLOSED' })))
    await flushPromises()

    expect(updateCase).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('gives a viewer no close or reopen action', async () => {
    const { mount } = setup({ roles: ['VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="close-case"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="reopen-case"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="case-status"]').text()).toBe('Open')

    wrapper.unmount()
  })
})

describe('CaseBar: conflict', () => {
  it('tells the moderator the case changed, overwrites nothing and offers a reload', async () => {
    const { mount } = setup({ updateCase: async () => fail('stale', 409, 'CONFLICT') })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="close-case"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="bar-conflict"]').attributes('role')).toBe('alert')
    expect(wrapper.emitted('updated'), 'nothing was saved').toBeUndefined()
    expect(wrapper.emitted('message')).toBeUndefined()

    await wrapper.get('[data-testid="bar-reload"]').trigger('click')

    expect(wrapper.emitted('reload')).toHaveLength(1)

    wrapper.unmount()
  })

  it('clears the conflict when a fresh version of the case arrives', async () => {
    const { mount } = setup({ updateCase: async () => fail('stale', 409, 'CONFLICT') })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="close-case"]').trigger('click')
    await flushPromises()
    await wrapper.setProps({ kase: caseResponse({ version: 5 }) })

    expect(wrapper.find('[data-testid="bar-conflict"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows any other failure with the server message instead of the conflict notice', async () => {
    const { mount } = setup({ updateCase: async () => fail('the database is busy', 500, 'UNKNOWN') })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="close-case"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="bar-error"]').attributes('role')).toBe('alert')
    expect(wrapper.get('[data-testid="bar-error"]').text()).toBe('the database is busy')
    expect(wrapper.find('[data-testid="bar-conflict"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
