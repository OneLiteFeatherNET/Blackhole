import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { i18n } from '../../i18n'
import AccountMenu from './AccountMenu.vue'

function mountMenu(displayName: string | null = 'Dev Admin') {
  i18n.global.locale.value = 'de'
  return mount(AccountMenu, {
    props: { displayName },
    global: { plugins: [createPinia(), i18n] },
    attachTo: document.body,
  })
}

describe('AccountMenu', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('is closed at first and shows the initial of the name on its button', () => {
    const wrapper = mountMenu()

    const button = wrapper.get('[data-testid="account-menu"]')
    expect(button.text()).toBe('D')
    expect(button.attributes('aria-label')).toBe('Kontomenü')
    expect(button.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-testid="sign-out"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('offers account, theme, language and sign-out once opened', async () => {
    const wrapper = mountMenu()

    await wrapper.get('[data-testid="account-menu"]').trigger('click')

    expect(wrapper.get('[data-testid="account-menu"]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-testid="signed-in-as"]').text()).toBe('Angemeldet als Dev Admin')
    expect(wrapper.findAll('[role="combobox"]'), 'theme and language').toHaveLength(2)

    await wrapper.get('[data-testid="account"]').trigger('click')
    expect(wrapper.emitted('account'), 'account').toHaveLength(1)
    expect(wrapper.find('[data-testid="sign-out"]').exists(), 'closed after a choice').toBe(false)

    await wrapper.get('[data-testid="account-menu"]').trigger('click')
    await wrapper.get('[data-testid="sign-out"]').trigger('click')
    expect(wrapper.emitted('signOut'), 'sign out').toHaveLength(1)

    wrapper.unmount()
  })

  it('closes with Escape and gives the focus back to its button', async () => {
    const wrapper = mountMenu()
    await wrapper.get('[data-testid="account-menu"]').trigger('click')

    await wrapper.get('[data-testid="signed-in-as"]').trigger('keydown', { key: 'Escape' })

    expect(wrapper.find('[data-testid="sign-out"]').exists(), 'open').toBe(false)
    expect(document.activeElement).toBe(wrapper.get('[data-testid="account-menu"]').element)

    wrapper.unmount()
  })

  it('closes on a press outside but not on a press inside', async () => {
    const wrapper = mountMenu()
    await wrapper.get('[data-testid="account-menu"]').trigger('click')

    wrapper.get('[data-testid="signed-in-as"]').element.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="sign-out"]').exists(), 'still open').toBe(true)

    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="sign-out"]').exists(), 'closed').toBe(false)

    wrapper.unmount()
  })

  it('offers only the theme and the language while nobody is signed in', async () => {
    const wrapper = mountMenu(null)

    await wrapper.get('[data-testid="account-menu"]').trigger('click')

    expect(wrapper.findAll('[role="combobox"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="sign-out"]').exists(), 'sign-out').toBe(false)
    expect(wrapper.find('[data-testid="account"]').exists(), 'account').toBe(false)

    wrapper.unmount()
  })
})
