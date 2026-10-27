import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { i18n } from '../i18n'
import { LAST_WORKSPACE_STORAGE_KEY } from '../nav/workspaces'
import { createAppRouter } from '../router'
import { piniaWithRoles } from '../test/mount'
import WorkspaceSwitcher from './WorkspaceSwitcher.vue'

async function mountSwitcher(roles: string[], path = '/cases', locale: 'en' | 'de' = 'en') {
  i18n.global.locale.value = locale
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

const TRIGGER = '[data-testid="workspace-switcher"]'
const MENU = '[data-testid="workspace-menu"]'

async function open(wrapper: Awaited<ReturnType<typeof mountSwitcher>>['wrapper']) {
  await wrapper.get(TRIGGER).trigger('click')
  await flushPromises()
  expect(wrapper.find(MENU).exists(), 'menu opened by the button').toBe(true)
}

function items(wrapper: Awaited<ReturnType<typeof mountSwitcher>>['wrapper']) {
  return wrapper.findAll('[role="menuitem"]')
}

describe('WorkspaceSwitcher', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
    document.body.innerHTML = ''
  })

  describe('visibility', () => {
    it('is shown to an administrator', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN', 'MODERATOR', 'VIEWER'])

      expect(wrapper.find(TRIGGER).exists()).toBe(true)

      wrapper.unmount()
    })

    it.each([['MODERATOR'], ['VIEWER']])('renders nothing for a %s', async (role) => {
      const { wrapper } = await mountSwitcher([role])

      expect(wrapper.find(TRIGGER).exists()).toBe(false)
      expect(wrapper.html()).toBe('<!--v-if-->')

      wrapper.unmount()
    })
  })

  describe('the button', () => {
    it('is a menu button named after the current workspace, closed at first', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')

      const trigger = wrapper.get(TRIGGER)
      expect(trigger.element.tagName).toBe('BUTTON')
      expect(trigger.text()).toBe('Moderation')
      expect(trigger.attributes('aria-haspopup')).toBe('menu')
      expect(trigger.attributes('aria-expanded')).toBe('false')
      expect(wrapper.find(MENU).exists()).toBe(false)

      wrapper.unmount()
    })

    it('names the administration workspace on an administration page', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/admin/sections')

      expect(wrapper.get(TRIGGER).text()).toBe('Administration')

      wrapper.unmount()
    })

    it('no longer shows the workspaces as a row of links', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')

      expect(wrapper.findAll('a').length, 'links').toBe(0)
      expect(wrapper.find('[role="group"]').exists(), 'toggle group').toBe(false)
      expect(wrapper.find('[data-testid="workspace-administration"]').exists(), 'administration toggle').toBe(false)

      wrapper.unmount()
    })
  })

  describe('opening and closing', () => {
    it('lists both workspaces with a short description each once opened', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')

      await open(wrapper)

      expect(wrapper.get(TRIGGER).attributes('aria-expanded')).toBe('true')
      expect(wrapper.get(MENU).attributes('role')).toBe('menu')
      expect(items(wrapper).map((item) => item.find('[data-testid="workspace-label"]').text())).toEqual(['Moderation', 'Administration'])
      expect(items(wrapper).map((item) => item.find('[data-testid="workspace-hint"]').text())).toEqual([
        'Cases, appeals, reports and players',
        'Templates, sections, client rules, staff accounts and imports',
      ])

      wrapper.unmount()
    })

    it('closes when its button is pressed again', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')
      await open(wrapper)

      await wrapper.get(TRIGGER).trigger('click')

      expect(wrapper.find(MENU).exists()).toBe(false)
      expect(wrapper.get(TRIGGER).attributes('aria-expanded')).toBe('false')

      wrapper.unmount()
    })

    it('closes with Escape and returns the focus to its button', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')
      await open(wrapper)

      await wrapper.get(MENU).trigger('keydown', { key: 'Escape' })

      expect(wrapper.find(MENU).exists()).toBe(false)
      expect(document.activeElement).toBe(wrapper.get(TRIGGER).element)

      wrapper.unmount()
    })

    it('closes when the viewer presses outside it', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')
      await open(wrapper)

      document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
      await flushPromises()

      expect(wrapper.find(MENU).exists()).toBe(false)

      wrapper.unmount()
    })
  })

  describe('keyboard', () => {
    it('ArrowDown on the button opens the menu and focuses the first item', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')

      await wrapper.get(TRIGGER).trigger('keydown', { key: 'ArrowDown' })
      await flushPromises()

      expect(wrapper.find(MENU).exists()).toBe(true)
      expect(document.activeElement).toBe(items(wrapper)[0]!.element)

      wrapper.unmount()
    })

    it('ArrowDown moves to the next item and wraps to the first', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')
      await open(wrapper)

      await wrapper.get(MENU).trigger('keydown', { key: 'ArrowDown' })
      expect(document.activeElement).toBe(items(wrapper)[1]!.element)

      await wrapper.get(MENU).trigger('keydown', { key: 'ArrowDown' })
      expect(document.activeElement).toBe(items(wrapper)[0]!.element)

      wrapper.unmount()
    })

    it('ArrowUp from the first item wraps to the last', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')
      await open(wrapper)

      await wrapper.get(MENU).trigger('keydown', { key: 'ArrowUp' })

      expect(document.activeElement).toBe(items(wrapper)[1]!.element)

      wrapper.unmount()
    })

    it('Home and End jump to the first and the last item', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')
      await open(wrapper)

      await wrapper.get(MENU).trigger('keydown', { key: 'End' })
      expect(document.activeElement).toBe(items(wrapper)[1]!.element)

      await wrapper.get(MENU).trigger('keydown', { key: 'Home' })
      expect(document.activeElement).toBe(items(wrapper)[0]!.element)

      wrapper.unmount()
    })
  })

  describe('choosing a workspace', () => {
    it('navigates to the Administration home, closes and keeps the focus on the button', async () => {
      const { wrapper, router } = await mountSwitcher(['ADMIN'], '/cases')
      await open(wrapper)

      await wrapper.get('[data-testid="workspace-administration"]').trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.fullPath).toBe('/admin/templates')
      expect(wrapper.find(MENU).exists()).toBe(false)
      expect(document.activeElement).toBe(wrapper.get(TRIGGER).element)

      wrapper.unmount()
    })

    it('navigates to the Moderation home from an administration page', async () => {
      const { wrapper, router } = await mountSwitcher(['ADMIN'], '/admin/sections')
      await open(wrapper)

      await wrapper.get('[data-testid="workspace-moderation"]').trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.fullPath).toBe('/')

      wrapper.unmount()
    })

    it('remembers the chosen workspace in localStorage', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/cases')
      await open(wrapper)

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
        await open(wrapper)

        await wrapper.get('[data-testid="workspace-administration"]').trigger('click')
        await flushPromises()

        expect(router.currentRoute.value.fullPath).toBe('/admin/templates')
        wrapper.unmount()
      } finally {
        Storage.prototype.setItem = original
      }
    })
  })

  describe('current workspace', () => {
    it('marks the workspace of the current route with aria-current and a check', async () => {
      const { wrapper } = await mountSwitcher(['ADMIN'], '/admin/sections')
      await open(wrapper)

      const administration = wrapper.get('[data-testid="workspace-administration"]')
      const moderation = wrapper.get('[data-testid="workspace-moderation"]')
      expect(administration.attributes('aria-current')).toBe('true')
      expect(administration.find('[data-testid="workspace-check"]').exists()).toBe(true)
      expect(moderation.attributes('aria-current')).toBeUndefined()
      expect(moderation.find('[data-testid="workspace-check"]').exists()).toBe(false)

      wrapper.unmount()
    })
  })
})
