import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { i18n } from '../i18n'
import { LAST_WORKSPACE_STORAGE_KEY } from '../nav/workspaces'
import { createAppRouter } from '../router'
import { piniaWithRoles } from '../test/mount'
import WorkspaceSwitcher from './WorkspaceSwitcher.vue'

async function mountSwitcher(roles: string[], path = '/cases') {
  i18n.global.locale.value = 'en'
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  const wrapper = mount(WorkspaceSwitcher, {
    global: { plugins: [piniaWithRoles(roles), i18n, router] },
    attachTo: document.body,
  })
  await flushPromises()
  return { wrapper, router }
}

describe('WorkspaceSwitcher', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('offers Moderation and Administration to an administrator', async () => {
    const { wrapper } = await mountSwitcher(['ADMIN', 'MODERATOR', 'VIEWER'])

    expect(wrapper.findAll('a[data-testid^="workspace-"]').map((link) => link.text())).toEqual(['Moderation', 'Administration'])

    wrapper.unmount()
  })

  it.each([['MODERATOR'], ['VIEWER']])('renders nothing for a %s', async (role) => {
    const { wrapper } = await mountSwitcher([role])

    expect(wrapper.find('[data-testid="workspace-switcher"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('marks the workspace of the current route', async () => {
    const { wrapper } = await mountSwitcher(['ADMIN'], '/admin/sections')

    expect(wrapper.get('[data-testid="workspace-administration"]').attributes('aria-current')).toBe('true')
    expect(wrapper.get('[data-testid="workspace-moderation"]').attributes('aria-current')).toBeUndefined()

    wrapper.unmount()
  })

  it('opens the administration home when Administration is chosen', async () => {
    const { wrapper, router } = await mountSwitcher(['ADMIN'], '/cases')

    await wrapper.get('[data-testid="workspace-administration"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/admin/templates')

    wrapper.unmount()
  })

  it('remembers the chosen workspace in localStorage', async () => {
    const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')

    await wrapper.get('[data-testid="workspace-administration"]').trigger('click')
    await flushPromises()

    expect(localStorage.getItem(LAST_WORKSPACE_STORAGE_KEY)).toBe('administration')

    wrapper.unmount()
  })

  it('still switches when localStorage throws', async () => {
    const original = Storage.prototype.setItem
    Storage.prototype.setItem = () => {
      throw new Error('blocked')
    }
    try {
      const { wrapper, router } = await mountSwitcher(['ADMIN'], '/cases')

      await wrapper.get('[data-testid="workspace-administration"]').trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.fullPath).toBe('/admin/templates')
      wrapper.unmount()
    } finally {
      Storage.prototype.setItem = original
    }
  })
})
