import { describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import IntelPolicyView from './IntelPolicyView.vue'

const policies = [
  { classification: 'VPN', policy: 'FLAG' },
  { classification: 'HOSTING', policy: 'ALLOW' },
  { classification: 'PROXY', policy: 'FLAG' },
]

describe('IntelPolicyView', () => {
  it('shows the policy of each class and the two X4BNet list sources with their attribution', async () => {
    const { wrapper } = await mountWithApp(IntelPolicyView, {
      api: mockApi({ intelPolicies: vi.fn(async () => ok(policies)) }),
      path: '/intel/policy',
      roles: ['MODERATOR', 'VIEWER'],
    })

    expect(wrapper.get('[data-testid="policy-VPN"]').text()).toContain('Flag')
    expect(wrapper.findAll('[data-testid="source-attribution"]')).toHaveLength(2)

    wrapper.unmount()
  })

  it('shows the failure of the policy request as an alert', async () => {
    const { wrapper } = await mountWithApp(IntelPolicyView, {
      api: mockApi({ intelPolicies: vi.fn(async () => fail('boom', 500, 'UNKNOWN')) }),
      path: '/intel/policy',
      roles: ['ADMIN', 'MODERATOR', 'VIEWER'],
    })

    expect(wrapper.text()).toContain('boom')

    wrapper.unmount()
  })
})
