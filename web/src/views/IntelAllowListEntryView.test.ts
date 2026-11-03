import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import IntelAllowListEntryView from './IntelAllowListEntryView.vue'

const OWNER = 'c'.repeat(128)
const NOW = Date.UTC(2030, 0, 3, 12, 0)

const entry = { owner: OWNER, reason: 'Shared network', actorOid: '00000000-0000-4000-8000-0000000000b2', createdAt: NOW, expired: false, expiresAt: null }

function setup(overrides: Record<string, unknown> = {}, roles: string[] = ['MODERATOR', 'VIEWER'], path = `/intel/allow-list/${OWNER}`, owner = OWNER) {
  const api = {
    intelAllowListEntry: vi.fn(async () => ok(entry)),
    removeIntelAllowListEntry: vi.fn(async () => ok(undefined)),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(IntelAllowListEntryView, { api: mockApi(api), roles, path, props: { owner } }) }
}

describe('IntelAllowListEntryView', () => {
  it('reads the exemption by its owner hash and shows its reason', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    expect(api.intelAllowListEntry).toHaveBeenCalledWith(OWNER)
    expect(wrapper.text()).toContain('Shared network')
    wrapper.unmount()
  })

  it('does not ask the server for a value that is not an owner hash', async () => {
    const { api, mount } = setup({}, ['MODERATOR'], '/intel/allow-list/not-a-hash', 'not-a-hash')
    const { wrapper } = await mount()
    await flushPromises()

    expect(api.intelAllowListEntry).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="item-missing"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows the not-found notice for an owner without an exemption', async () => {
    const { mount } = setup({ intelAllowListEntry: vi.fn(async () => fail('no such exemption', 404, 'NOT_FOUND')) })
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="item-missing"]').text()).toContain('does not exist any more')
    wrapper.unmount()
  })

  it('offers the removal to a moderator and not to a viewer', async () => {
    const moderator = setup()
    const first = await moderator.mount()
    await flushPromises()
    expect(first.wrapper.find('[data-testid="remove-open"]').exists()).toBe(true)
    first.wrapper.unmount()

    const viewer = setup({}, ['VIEWER'])
    const second = await viewer.mount()
    await flushPromises()
    expect(second.wrapper.find('[data-testid="remove-open"]').exists()).toBe(false)
    second.wrapper.unmount()
  })

  it('sends nothing when the removal route is only opened', async () => {
    const { api, mount } = setup({}, ['MODERATOR'], `/intel/allow-list/${OWNER}/remove`)
    const { wrapper } = await mount()
    await flushPromises()

    expect(api.removeIntelAllowListEntry).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="remove-body"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('removes the exemption on confirmation and returns to the list', async () => {
    const { api, mount } = setup({}, ['MODERATOR'], `/intel/allow-list/${OWNER}/remove`)
    const { wrapper, router } = await mount()
    await flushPromises()

    await wrapper.get('[data-testid="remove-confirm"]').trigger('click')
    await flushPromises()

    expect(api.removeIntelAllowListEntry).toHaveBeenCalledWith(OWNER)
    expect(router.currentRoute.value.name).toBe('intel-allow-list')
    wrapper.unmount()
  })

  it('keeps the exemption and says so when the removal is refused', async () => {
    const { mount } = setup(
      { removeIntelAllowListEntry: vi.fn(async () => fail('The exemption is locked.', 403, 'UNKNOWN')) },
      ['MODERATOR'],
      `/intel/allow-list/${OWNER}/remove`,
    )
    const { wrapper, router } = await mount()
    await flushPromises()

    await wrapper.get('[data-testid="remove-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="remove-error"]').text()).toContain('The exemption is locked.')
    expect(router.currentRoute.value.name).toBe('intel-allow-entry-remove')
    wrapper.unmount()
  })
})
