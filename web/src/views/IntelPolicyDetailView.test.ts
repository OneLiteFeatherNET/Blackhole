import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import { chooseOption } from '../test/select'
import IntelPolicyDetailView from './IntelPolicyDetailView.vue'

const policies = [
  { classification: 'VPN', policy: 'FLAG' },
  { classification: 'HOSTING', policy: 'ALLOW' },
  { classification: 'PROXY', policy: 'FLAG' },
]

function setup(classification: string, roles: string[], overrides: Record<string, unknown> = {}) {
  const api = {
    intelPolicies: vi.fn(async () => ok(policies)),
    setIntelPolicy: vi.fn(async () => ok({ classification: 'VPN', policy: 'DENY' })),
    ...overrides,
  }
  const path = `/intel/policy/${classification}${overrides.edit ? '/edit' : ''}`
  return { api, mount: () => mountWithApp(IntelPolicyDetailView, { api: mockApi(api), roles, path, props: { classification } }) }
}

describe('IntelPolicyDetailView', () => {
  it('shows the current policy of the class', async () => {
    const { mount } = setup('HOSTING', ['VIEWER'])
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="policy-current"]').text()).toBe('Allow')
    wrapper.unmount()
  })

  it('shows the not-found notice for a name that is not a login class', async () => {
    const { api, mount } = setup('ASN', ['VIEWER'])
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="item-missing"]').exists()).toBe(true)
    expect(api.intelPolicies).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('offers the change to an administrator only', async () => {
    const admin = setup('VPN', ['ADMIN', 'MODERATOR', 'VIEWER'])
    const first = await admin.mount()
    await flushPromises()
    expect(first.wrapper.find('[data-testid="policy-change"]').exists()).toBe(true)
    first.wrapper.unmount()

    const moderator = setup('VPN', ['MODERATOR', 'VIEWER'])
    const second = await moderator.mount()
    await flushPromises()
    expect(second.wrapper.find('[data-testid="policy-change"]').exists()).toBe(false)
    second.wrapper.unmount()
  })

  it('asks for confirmation with the previous value named, and sends the change only once confirmed', async () => {
    const { api, mount } = setup('VPN', ['ADMIN', 'MODERATOR', 'VIEWER'], { edit: true })
    const { wrapper } = await mount()
    await flushPromises()

    await chooseOption(wrapper.get('[data-testid="policy-select"] [role="combobox"]'), 'DENY')
    await wrapper.get('[data-testid="policy-save"]').trigger('click')
    await flushPromises()

    expect(api.setIntelPolicy, 'nothing is sent before the confirmation').not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="policy-confirmation"]').text()).toContain('Flag')

    await wrapper.get('[data-testid="policy-confirm"]').trigger('click')
    await flushPromises()

    expect(api.setIntelPolicy).toHaveBeenCalledWith('VPN', 'DENY')
    wrapper.unmount()
  })

  it('shows the refusal of the server in the dialog', async () => {
    const { mount } = setup('VPN', ['ADMIN', 'MODERATOR', 'VIEWER'], {
      edit: true,
      setIntelPolicy: vi.fn(async () => fail('Only an administrator may deny.', 403, 'UNKNOWN')),
    })
    const { wrapper } = await mount()
    await flushPromises()

    await chooseOption(wrapper.get('[data-testid="policy-select"] [role="combobox"]'), 'DENY')
    await wrapper.get('[data-testid="policy-save"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="policy-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="policy-error"]').text()).toContain('Only an administrator may deny.')
    wrapper.unmount()
  })
})
