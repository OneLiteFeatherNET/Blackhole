import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fail, facts, mountPortal, ok, signedInApi } from '../test/helpers'

let mounted: Awaited<ReturnType<typeof mountPortal>> | undefined
afterEach(() => {
  mounted?.wrapper.unmount()
  mounted = undefined
})

describe('punishment page', () => {
  it('lists the facts as a description list', async () => {
    mounted = await mountPortal(signedInApi())

    const text = mounted.wrapper.get('dl').text()
    expect(text).not.toContain('7d1f0c52')
    expect(text, 'the internal id is not shown').not.toContain('Reference')
    expect(document.body.textContent).not.toContain('7d1f0c52')
    expect(text).toContain('Hacked client')
    expect(text).toContain('Survival')
    expect(text).toContain('2 items on file')
    expect(mounted.wrapper.findAll('dl time').length).toBeGreaterThanOrEqual(2)
  })

  it('shows permanent instead of an expiry', async () => {
    mounted = await mountPortal(signedInApi({}, facts({ permanent: true, expiresAt: null })))

    expect(mounted.wrapper.get('dl').text()).toContain('Permanent')
  })

  it('offers the appeal form when an appeal can be filed', async () => {
    mounted = await mountPortal(signedInApi())

    const button = mounted.wrapper.findAll('button').find((b) => b.text() === 'File an appeal')
    expect(button).toBeDefined()
    await button!.trigger('click')

    expect(mounted.wrapper.get('h1').text()).toBe('File an appeal')
  })

  it('shows the date the appeal becomes available when it is too early', async () => {
    mounted = await mountPortal(signedInApi({}, facts({ appealState: 'NOT_YET', availableFrom: '2026-11-15T00:00:00Z' })))

    const section = mounted.wrapper.get('[aria-labelledby="appeal-title"]')
    expect(section.text()).toMatch(/You can file an appeal from .*2026/)
    expect(section.findAll('button')).toHaveLength(0)
  })

  it('shows the forfeited notice and no way to appeal', async () => {
    mounted = await mountPortal(signedInApi({}, facts({ appealState: 'FORFEITED' })))

    const section = mounted.wrapper.get('[aria-labelledby="appeal-title"]')
    expect(section.text()).toContain('right to appeal this punishment was lost')
    expect(section.findAll('button')).toHaveLength(0)
  })

  it('shows the frozen notice', async () => {
    mounted = await mountPortal(signedInApi({}, facts({ appealState: 'FROZEN' })))

    expect(mounted.wrapper.get('[aria-labelledby="appeal-title"]').text()).toContain('Your appeal is frozen')
  })

  it('shows only the facts and a notice for a limited session', async () => {
    mounted = await mountPortal(
      signedInApi({ session: async () => ok({ csrf: 'c', expiresAt: '2099-01-01T00:00:00Z', scope: 'LIMITED' as const }) }, facts({ appealState: 'OPEN' })),
    )

    const section = mounted.wrapper.get('[aria-labelledby="appeal-title"]')
    expect(section.text()).toContain('shared publicly')
    expect(section.findAll('button'), 'no conversation, no appeal').toHaveLength(0)
  })

  it('omits the date for a punishment that is over', async () => {
    mounted = await mountPortal(signedInApi({}, facts({ appealState: 'DECIDED', state: 'EXPIRED', availableFrom: undefined })))

    const section = mounted.wrapper.get('[aria-labelledby="appeal-title"]')
    expect(section.text()).toContain('nothing further to appeal')
    expect(section.findAll('button').map((b) => b.text())).toEqual(['Open conversation'])
  })

  it('shows the cooldown after a denial', async () => {
    mounted = await mountPortal(signedInApi({}, facts({ appealState: 'COOLDOWN', availableFrom: '2026-11-15T00:00:00Z' })))

    expect(mounted.wrapper.get('[aria-labelledby="appeal-title"]').text()).toMatch(/was denied.*2026/)
  })
})

describe('logout', () => {
  it('signs out, forgets the punishment and shows the login', async () => {
    const logout = vi.fn(async () => ok(null))
    mounted = await mountPortal(signedInApi({ logout }))

    await mounted.wrapper.findAll('button').find((b) => b.text() === 'Sign out')!.trigger('click')
    await flushPromises()

    expect(logout).toHaveBeenCalledOnce()
    expect(mounted.wrapper.find('input#ban-id').exists()).toBe(true)
    expect(document.body.textContent).not.toContain('Hacked client')
  })

  it('discards unsent drafts', async () => {
    mounted = await mountPortal(signedInApi({ logout: async () => ok(null) }))
    mounted.session.state.drafts.message = 'unsent'

    await mounted.session.logout()

    expect(mounted.session.state.drafts).toEqual({ statement: '', message: '', files: [] })
  })
})

describe('session expiry', () => {
  it('shows the expiry page when the backend ends the session', async () => {
    mounted = await mountPortal(signedInApi({ punishment: async () => ok(facts()) }))

    mounted.session.alive(fail('SESSION_EXPIRED', 401))
    await flushPromises()

    expect(mounted.wrapper.get('h1').text()).toBe('Your session has ended')
    expect(mounted.wrapper.find('[role="status"]').exists()).toBe(true)
    expect(document.body.textContent).not.toContain('Hacked client')
  })

  it('keeps an unsent draft in memory across the expiry page and the next sign-in', async () => {
    mounted = await mountPortal(signedInApi())
    mounted.session.state.drafts.statement = 'my long statement'

    mounted.session.expire()
    await flushPromises()
    expect(mounted.wrapper.text()).toContain('Text you had not sent yet is kept')
    await mounted.wrapper.findAll('button').find((b) => b.text() === 'Sign in again')!.trigger('click')

    expect(mounted.wrapper.find('input#ban-id').exists()).toBe(true)
    expect(mounted.session.state.drafts.statement).toBe('my long statement')
  })

  it('treats a bootstrap without a session as signed out', async () => {
    mounted = await mountPortal(signedInApi({ session: async () => fail('SESSION_EXPIRED', 401) }))

    expect(mounted.wrapper.find('input#ban-id').exists()).toBe(true)
  })
})
