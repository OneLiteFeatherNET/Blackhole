import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fail, fakeApi, mountPortal, ok, SESSION, facts } from '../test/helpers'

const TYPED = ' k7m2-o9qx '
const NORMALISED = 'K7M209QX'

let mounted: Awaited<ReturnType<typeof mountPortal>> | undefined
afterEach(() => {
  mounted?.wrapper.unmount()
  mounted = undefined
  vi.restoreAllMocks()
})

const signedOut = () => fail<never>('SESSION_EXPIRED', 401)

describe('login page', () => {
  it('shows a labelled Ban ID field that the browser does not remember', async () => {
    mounted = await mountPortal(fakeApi({ session: async () => signedOut() }))

    const input = mounted.wrapper.get('input#ban-id')
    expect(mounted.wrapper.get('label[for="ban-id"]').text()).toBe('Ban ID')
    expect(input.attributes('autocomplete')).toBe('off')
    expect(input.attributes('aria-describedby')).toContain('ban-id-hint')
  })

  it('has landmarks, a skip link and one level-one heading', async () => {
    mounted = await mountPortal(fakeApi({ session: async () => signedOut() }))

    const { wrapper } = mounted
    expect(wrapper.find('header').exists(), 'banner').toBe(true)
    expect(wrapper.find('main#main').exists(), 'main').toBe(true)
    expect(wrapper.find('footer').exists(), 'contentinfo').toBe(true)
    expect(wrapper.get('a[href="#main"]').text()).toBe('Skip to content')
    expect(wrapper.findAll('h1')).toHaveLength(1)
  })

  it('submits on Enter from the field, normalised', async () => {
    const login = vi.fn(async () => fail<never>('INVALID_BAN_ID', 401))
    mounted = await mountPortal(fakeApi({ session: async () => signedOut(), login }))

    await mounted.wrapper.get('input#ban-id').setValue(TYPED)
    await mounted.wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(login).toHaveBeenCalledExactlyOnceWith(NORMALISED)
  })

  it('clears the field and keeps the Ban ID out of the page after submitting', async () => {
    mounted = await mountPortal(fakeApi({ session: async () => signedOut(), login: async () => fail<never>('INVALID_BAN_ID', 401) }))

    await mounted.wrapper.get('input#ban-id').setValue(TYPED)
    await mounted.wrapper.get('form').trigger('submit')
    await flushPromises()

    expect((mounted.wrapper.get('input#ban-id').element as HTMLInputElement).value).toBe('')
    expect(document.body.innerHTML).not.toContain(NORMALISED)
    expect(JSON.stringify(mounted.session.state)).not.toContain(NORMALISED)
  })

  it('announces a failed login in an alert region with the same text for every cause', async () => {
    const texts: string[] = []
    for (const reply of [fail<never>('INVALID_BAN_ID', 401), fail<never>('VALIDATION', 400), fail<never>('NOT_FOUND', 404)]) {
      mounted = await mountPortal(fakeApi({ session: async () => signedOut(), login: async () => reply }))
      await mounted.wrapper.get('input#ban-id').setValue(TYPED)
      await mounted.wrapper.get('form').trigger('submit')
      await flushPromises()
      texts.push(mounted.wrapper.get('#ban-id-error[role="alert"]').text())
      mounted.wrapper.unmount()
    }
    mounted = undefined

    expect(texts[0]).toContain('could not be used')
    expect(new Set(texts).size, 'identical text').toBe(1)
  })

  it('answers input that cannot be a Ban ID like a wrong one, without calling the backend', async () => {
    const login = vi.fn()
    mounted = await mountPortal(fakeApi({ session: async () => signedOut(), login }))

    await mounted.wrapper.get('input#ban-id').setValue('abc')
    await mounted.wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(login).not.toHaveBeenCalled()
    expect(mounted.wrapper.get('#ban-id-error').text()).toContain('could not be used')
  })

  it('says so when the portal cannot be reached, which is not a wrong Ban ID', async () => {
    mounted = await mountPortal(fakeApi({ session: async () => signedOut(), login: async () => fail<never>('UNAVAILABLE', 503) }))

    await mounted.wrapper.get('input#ban-id').setValue(TYPED)
    await mounted.wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mounted.wrapper.get('#ban-id-error').text()).toContain('cannot be reached')
  })

  it('shows the punishment after a good login and holds the CSRF value in memory only', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const cookie = vi.spyOn(document, 'cookie', 'set')
    mounted = await mountPortal(
      fakeApi({ session: async () => signedOut(), login: async () => ok(SESSION), punishment: async () => ok(facts()) }),
    )

    await mounted.wrapper.get('input#ban-id').setValue(TYPED)
    await mounted.wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mounted.wrapper.get('h1').text()).toBe('Your punishment')
    expect(setItem, 'no localStorage or sessionStorage write').not.toHaveBeenCalled()
    expect(cookie, 'the page never writes a cookie').not.toHaveBeenCalled()
    expect(document.body.innerHTML).not.toContain(SESSION.csrf)
  })

  it('disables the button while checking', async () => {
    let release: (value: Awaited<ReturnType<ReturnType<typeof fakeApi>['login']>>) => void = () => undefined
    mounted = await mountPortal(fakeApi({ session: async () => signedOut(), login: () => new Promise((resolve) => (release = resolve)) }))

    await mounted.wrapper.get('input#ban-id').setValue(TYPED)
    await mounted.wrapper.get('form').trigger('submit')

    expect(mounted.wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    release(fail('INVALID_BAN_ID', 401))
    await flushPromises()
    expect(mounted.wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })

  it('switches to German and back without losing the field', async () => {
    mounted = await mountPortal(fakeApi({ session: async () => signedOut() }))

    await mounted.wrapper.get('input#ban-id').setValue('abc')
    await mounted.wrapper.get('button[lang="de"]').trigger('click')

    expect(mounted.wrapper.get('label[for="ban-id"]').text()).toBe('Ban-ID')
    expect(document.documentElement.lang).toBe('de')
    expect((mounted.wrapper.get('input#ban-id').element as HTMLInputElement).value).toBe('abc')
    expect(mounted.wrapper.get('button[lang="de"]').attributes('aria-pressed')).toBe('true')
  })
})
