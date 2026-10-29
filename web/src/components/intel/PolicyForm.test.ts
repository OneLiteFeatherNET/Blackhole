import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { IntelPolicyEntry } from '../../api/types'
import { chooseOption, listOptions } from '../../test/select'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import PolicyForm from './PolicyForm.vue'

const policies: IntelPolicyEntry[] = [
  { classification: 'VPN', policy: 'FLAG' },
  { classification: 'HOSTING', policy: 'ALLOW' },
  { classification: 'PROXY', policy: 'FLAG' },
]

function mountForm(props: { administrator: boolean }, setIntelPolicy = vi.fn(async () => ok({ classification: 'VPN', policy: 'ALLOW', previous: 'FLAG' } as IntelPolicyEntry))) {
  return mountWithApp(PolicyForm, {
    api: mockApi({ setIntelPolicy }),
    props: { policies, ...props },
  })
}

describe('PolicyForm', () => {
  it('offers DENY as a policy to an administrator', async () => {
    const { wrapper } = await mountForm({ administrator: true })

    const values = (await listOptions(wrapper.get('[data-testid="policy-VPN"] [role="combobox"]'))).map((option) => option.value)

    expect(values).toEqual(['ALLOW', 'FLAG', 'DENY'])

    wrapper.unmount()
  })

  it('shows the policies read-only to a moderator, with no change control and no DENY', async () => {
    const { wrapper } = await mountForm({ administrator: false })

    expect(wrapper.find('[role="combobox"]').exists(), 'no dropdown for a moderator').toBe(false)
    expect(wrapper.find('[data-testid="policy-save"]').exists(), 'no save for a moderator').toBe(false)
    expect(wrapper.get('[data-testid="policy-VPN"]').text()).toContain('Flag')

    wrapper.unmount()
  })

  it('names the previous value in the confirmation before a change is saved', async () => {
    const setIntelPolicy = vi.fn(async () => ok({ classification: 'VPN', policy: 'ALLOW', previous: 'FLAG' } as IntelPolicyEntry))
    const { wrapper } = await mountForm({ administrator: true }, setIntelPolicy)

    await chooseOption(wrapper.get('[data-testid="policy-VPN"] [role="combobox"]'), 'ALLOW')
    await wrapper.get('[data-testid="policy-save"]').trigger('click')

    expect(setIntelPolicy, 'nothing is saved before the confirmation').not.toHaveBeenCalled()
    const confirmation = wrapper.get('[data-testid="policy-confirmation"]').text()
    expect(confirmation).toContain('Flag')
    expect(confirmation).toContain('Allow')

    wrapper.unmount()
  })

  it('saves the change once it is confirmed and reports it', async () => {
    const setIntelPolicy = vi.fn(async () => ok({ classification: 'VPN', policy: 'ALLOW', previous: 'FLAG' } as IntelPolicyEntry))
    const { wrapper } = await mountForm({ administrator: true }, setIntelPolicy)

    await chooseOption(wrapper.get('[data-testid="policy-VPN"] [role="combobox"]'), 'ALLOW')
    await wrapper.get('[data-testid="policy-save"]').trigger('click')
    await wrapper.get('[data-testid="policy-confirm"]').trigger('click')
    await flushPromises()

    expect(setIntelPolicy).toHaveBeenCalledWith('VPN', 'ALLOW')
    expect(wrapper.emitted('saved')).toEqual([['Policy for VPN saved as Allow.']])

    wrapper.unmount()
  })

  it('keeps the confirmation open with the refusal when the backend says no', async () => {
    const setIntelPolicy = vi.fn(async () => fail('only an admin may set DENY', 403, 'UNKNOWN'))
    const { wrapper } = await mountForm({ administrator: true }, setIntelPolicy)

    await chooseOption(wrapper.get('[data-testid="policy-VPN"] [role="combobox"]'), 'ALLOW')
    await wrapper.get('[data-testid="policy-save"]').trigger('click')
    await wrapper.get('[data-testid="policy-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="policy-error"]').text()).toContain('only an admin may set DENY')
    expect(wrapper.emitted('saved')).toBeUndefined()

    wrapper.unmount()
  })

  it('disables saving while the policy of a class is unchanged', async () => {
    const { wrapper } = await mountForm({ administrator: true })

    expect(wrapper.get('[data-testid="policy-save"]').attributes('disabled'), 'same value as stored').toBeDefined()

    wrapper.unmount()
  })
})
