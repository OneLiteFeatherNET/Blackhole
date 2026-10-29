import { describe, expect, it } from 'vitest'
import { mockApi, mountWithApp } from '../test/mount'
import IntelAllowListView from './IntelAllowListView.vue'

describe('IntelAllowListView', () => {
  it('offers the exemption form to a moderator', async () => {
    const { wrapper } = await mountWithApp(IntelAllowListView, { api: mockApi(), path: '/intel/allow-list', roles: ['MODERATOR', 'VIEWER'] })

    expect(wrapper.find('[data-testid="allow-list-submit"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('tells a viewer that only moderators add exemptions, with no form', async () => {
    const { wrapper } = await mountWithApp(IntelAllowListView, { api: mockApi(), path: '/intel/allow-list', roles: ['VIEWER'] })

    expect(wrapper.find('[data-testid="allow-list-submit"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="allow-list-readonly"]').text()).toContain('Only moderators')

    wrapper.unmount()
  })
})
