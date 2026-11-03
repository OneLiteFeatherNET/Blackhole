import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import IntelAllowListView from './IntelAllowListView.vue'

const OWNER = 'a'.repeat(128)
const NOW = Date.UTC(2030, 0, 3, 12, 0)

const entry = { owner: OWNER, reason: 'Shared network', actorOid: '00000000-0000-4000-8000-0000000000b2', createdAt: NOW, expired: false, expiresAt: null }

function setup(overrides: Record<string, unknown> = {}, roles: string[] = ['MODERATOR', 'VIEWER'], path = '/intel/allow-list') {
  const api = {
    intelAllowList: vi.fn(async () => ok({ entries: [entry], page: 0, size: 50, hasMore: false })),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(IntelAllowListView, { api: mockApi(api), roles, path }) }
}

describe('IntelAllowListView: the list', () => {
  it('lists the exemptions and links each row to its own page by the owner hash', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    const link = wrapper.get(`a[href="/intel/allow-list/${OWNER}"]`)
    expect(link.text()).toContain('aaaa')
    wrapper.unmount()
  })

  it('reads the page named in the query', async () => {
    const { api, mount } = setup({}, ['VIEWER'], '/intel/allow-list?page=2')
    const { wrapper } = await mount()
    await flushPromises()

    expect(api.intelAllowList).toHaveBeenCalledWith(2, 50)
    wrapper.unmount()
  })

  it('offers the next page as an address that keeps the list state', async () => {
    const { mount } = setup({ intelAllowList: vi.fn(async () => ok({ entries: [entry], page: 0, size: 50, hasMore: true })) })
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="allow-list-next"]').attributes('href')).toBe('/intel/allow-list?page=1')
    wrapper.unmount()
  })

  it('says so when a page has no exemptions', async () => {
    const { mount } = setup({ intelAllowList: vi.fn(async () => ok({ entries: [], page: 0, size: 50, hasMore: false })) })
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="allow-list-empty"]').text()).toContain('No exemptions on this page.')
    wrapper.unmount()
  })

  it('shows the failure of the request as an alert', async () => {
    const { mount } = setup({ intelAllowList: vi.fn(async () => fail('boom', 500, 'UNKNOWN')) })
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.text()).toContain('boom')
    wrapper.unmount()
  })
})

describe('IntelAllowListView: roles and the new exemption', () => {
  it('offers the new-exemption route to a moderator, which shows the form in a dialog', async () => {
    const { mount } = setup({}, ['MODERATOR', 'VIEWER'], '/intel/allow-list/new')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="allow-list-submit"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows no form on the list for a viewer, who is told that only moderators add exemptions', async () => {
    const { mount } = setup({}, ['VIEWER'])
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="new-allow-entry"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="allow-list-readonly"]').text()).toContain('Only moderators')
    wrapper.unmount()
  })
})
