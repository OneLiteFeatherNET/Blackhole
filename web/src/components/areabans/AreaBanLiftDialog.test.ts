import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { AreaBan } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import AreaBanLiftDialog from './AreaBanLiftDialog.vue'

const BAN = { id: 'b-1', kind: 'ASN', value: '4134', reason: 'Proxy farm', source: 'manual', createdAt: 1, active: true } as unknown as AreaBan

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    liftAreaBan: vi.fn(async () => ok({ ...BAN, active: false, liftedAt: 2 })),
    ...overrides,
  }
  return {
    api,
    mount: () => mountWithApp(AreaBanLiftDialog, { api: mockApi(api), roles: ['MODERATOR', 'VIEWER'], props: { ban: BAN } }),
  }
}

describe('AreaBanLiftDialog', () => {
  it('does not lift without a reason', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="confirm"]').trigger('click')

    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled')).toBeDefined()
    expect(api.liftAreaBan, 'no request without a reason').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('does not lift with a reason of spaces only', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="lift-reason"] input').setValue('   ')

    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled')).toBeDefined()
    expect(api.liftAreaBan).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('lifts the ban with the reason once one is given', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="lift-reason"] input').setValue(' False positive ')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.liftAreaBan).toHaveBeenCalledWith('b-1', ' False positive ')
    expect(wrapper.emitted('lifted')).toHaveLength(1)
    wrapper.unmount()
  })

  it('shows the refusal of the server and stays open', async () => {
    const { mount } = setup({ liftAreaBan: vi.fn(async () => fail('Already lifted.', 409, 'CONFLICT')) })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="lift-reason"] input').setValue('Reviewed')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('Already lifted.')
    expect(wrapper.emitted('lifted')).toBeUndefined()
    wrapper.unmount()
  })
})
