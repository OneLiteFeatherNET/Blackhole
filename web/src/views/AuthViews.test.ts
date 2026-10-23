import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import de from '../locales/de.json'
import { useSessionStore } from '../stores/session'
import { fakeAuthService } from '../test/auth'
import { mountWithApp } from '../test/mount'
import AuthErrorView from './AuthErrorView.vue'
import NoAccessView from './NoAccessView.vue'

describe('NoAccessView', () => {
  it('explains the situation and names the signed-in user', async () => {
    const { wrapper } = await mountWithApp(NoAccessView, { roles: [] })

    expect(wrapper.get('h2').text()).toBe('You have no access yet')
    expect(wrapper.text()).toContain('Signed in as Dev Admin')
    expect(wrapper.text()).toContain('has no role for Blackhole')

    wrapper.unmount()
  })

  it('says the page is for administrators when reached through an admin-only page, and links back', async () => {
    const { wrapper } = await mountWithApp(NoAccessView, { roles: ['VIEWER'], path: '/no-access?reason=admin' })

    expect(wrapper.get('h2').text(), 'heading').toBe('Administrators only')
    expect(wrapper.text(), 'body').toContain('only available to administrators')
    expect(wrapper.find('[data-testid="back-to-dashboard"]').exists(), 'link back').toBe(true)

    wrapper.unmount()
  })

  it('moves the focus to its heading, so a screen reader starts at the explanation', async () => {
    const { wrapper } = await mountWithApp(NoAccessView, { roles: [] })

    expect(document.activeElement, 'focused element').toBe(wrapper.get('h2').element)

    wrapper.unmount()
  })

  it('requests nothing from the backend', async () => {
    // mountWithApp provides an API that fails the test on any call.
    const { wrapper } = await mountWithApp(NoAccessView, { roles: [] })

    await flushPromises()

    expect(wrapper.exists()).toBe(true)

    wrapper.unmount()
  })

  it('signs out and forgets the user', async () => {
    const auth = fakeAuthService()
    const { wrapper } = await mountWithApp(NoAccessView, { roles: [], auth: auth.service })
    const session = useSessionStore()

    await wrapper.get('[data-testid="sign-out"]').trigger('click')
    await flushPromises()

    expect(auth.calls.signOut, 'sign-outs').toBe(1)
    expect(session.me, 'remembered user').toBeNull()

    wrapper.unmount()
  })
})

describe('AuthErrorView', () => {
  it.each([
    ['signin', 'The sign-in service could not be reached.'],
    ['callback', 'The sign-in could not be completed. Please try again.'],
    ['me', 'Your account could not be checked. Please try again.'],
    ['anything-else', 'The sign-in service could not be reached.'],
  ])('explains the reason %s', async (reason, text) => {
    const { wrapper } = await mountWithApp(AuthErrorView, { path: `/auth/error?reason=${reason}` })

    expect(wrapper.text()).toContain(text)

    wrapper.unmount()
  })

  it('is announced as an alert and takes the focus', async () => {
    const { wrapper } = await mountWithApp(AuthErrorView, { path: '/auth/error?reason=signin' })

    expect(wrapper.attributes('role')).toBe('alert')
    expect(document.activeElement, 'focused element').toBe(wrapper.get('h2').element)

    wrapper.unmount()
  })

  it('starts sign-in again from the start page', async () => {
    const auth = fakeAuthService()
    const { wrapper } = await mountWithApp(AuthErrorView, { path: '/auth/error?reason=callback', auth: auth.service })

    await wrapper.get('[data-testid="retry-sign-in"]').trigger('click')

    expect(auth.calls.signIn).toEqual(['/'])

    wrapper.unmount()
  })
})

describe('German sign-in texts', () => {
  it('spell their umlauts', () => {
    expect(de.auth.noAccess.body, 'no-access text').toContain('für')
    expect(de.auth.forbidden, 'forbidden text').toContain('Dafür')
    expect(de.state.error.UNAUTHORIZED, 'unauthorized text').toContain('gültig')
  })
})
