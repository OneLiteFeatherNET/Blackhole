import { flushPromises } from '@vue/test-utils'
import { getActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { caseResponse } from '../../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import BanIdPanel from './BanIdPanel.vue'

const SECRET = 'K7M2QX9D'

function setup(options: { roles?: string[]; kase?: Parameters<typeof caseResponse>[0]; extra?: Record<string, unknown> } = {}) {
  const api = {
    revealBanId: vi.fn(async () => ok(SECRET)),
    markBanIdLeaked: vi.fn(async () => ok({ changed: true })),
    reissueBanId: vi.fn(async () => ok({ generation: 2 })),
    ...options.extra,
  }
  return {
    api,
    mount: (kase = caseResponse(options.kase)) =>
      mountWithApp(BanIdPanel, { api: mockApi(api), roles: options.roles, path: '/cases/p-0001', props: { kase } }),
  }
}

const toggle = async (wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']) => {
  await wrapper.get('[data-testid="ban-id-toggle"]').trigger('click')
  await flushPromises()
}

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  sessionStorage.clear()
})

describe('BanIdPanel: reveal', () => {
  it('shows the Ban ID masked and does not fetch it on load', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(api.revealBanId).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="ban-id-value"]').attributes('data-revealed')).toBe('false')
    expect(wrapper.text()).not.toContain(SECRET)
    expect(wrapper.get('[data-testid="ban-id-value"]').text()).toContain('Ban ID hidden')

    wrapper.unmount()
  })

  it('fetches the Ban ID when asked, shows it and announces it without the value', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await toggle(wrapper)

    expect(api.revealBanId).toHaveBeenCalledOnce()
    expect(api.revealBanId).toHaveBeenCalledWith('p-0001')
    expect(wrapper.get('[data-testid="ban-id-value"]').text()).toBe(SECRET)
    expect(wrapper.get('[data-testid="ban-id-toggle"]').text()).toBe('Hide Ban ID')
    expect(wrapper.get('[data-testid="ban-id-status"]').text()).toBe('Ban ID shown.')
    expect(wrapper.get('[data-testid="ban-id-status"]').attributes('aria-live')).toBe('polite')

    wrapper.unmount()
  })

  it('hides it again on request and fetches it anew the next time', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await toggle(wrapper)
    await toggle(wrapper)
    expect(wrapper.text()).not.toContain(SECRET)

    await toggle(wrapper)
    expect(api.revealBanId).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('says so when the punishment has no Ban ID yet', async () => {
    const revealBanId = vi.fn(async () => fail('no Ban ID yet', 404, 'NOT_FOUND'))
    const { mount } = setup({ extra: { revealBanId } })
    const { wrapper } = await mount()

    await toggle(wrapper)

    expect(wrapper.get('[data-testid="ban-id-error"]').text()).toBe('This punishment has no Ban ID yet.')
    expect(wrapper.get('[data-testid="ban-id-value"]').attributes('data-revealed')).toBe('false')

    wrapper.unmount()
  })

  it('shows the server message when the reveal fails otherwise', async () => {
    const revealBanId = vi.fn(async () => fail('the key is not configured', 503, 'UNKNOWN'))
    const { mount } = setup({ extra: { revealBanId } })
    const { wrapper } = await mount()

    await toggle(wrapper)

    expect(wrapper.get('[data-testid="ban-id-error"]').text()).toBe('the key is not configured')

    wrapper.unmount()
  })

  it('gives a viewer the masked value without a reveal or any action', async () => {
    const { api, mount } = setup({ roles: ['VIEWER'] })
    const { wrapper } = await mount()

    for (const control of ['ban-id-toggle', 'ban-id-leak', 'ban-id-reissue']) {
      expect(wrapper.find(`[data-testid="${control}"]`).exists(), control).toBe(false)
    }
    expect(wrapper.get('[data-testid="ban-id-read-only"]').text()).toContain('moderator role')
    expect(api.revealBanId).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('BanIdPanel: the Ban ID is not kept anywhere', () => {
  it('keeps the revealed value out of storage, the store, the URL, the router state and the console', async () => {
    const consoleCalls: unknown[][] = []
    for (const method of ['log', 'info', 'warn', 'error', 'debug'] as const) {
      vi.spyOn(console, method).mockImplementation((...args: unknown[]) => void consoleCalls.push(args))
    }
    const { mount } = setup()
    const { wrapper, router } = await mount()

    await toggle(wrapper)
    expect(wrapper.text()).toContain(SECRET)

    const everywhere = {
      localStorage: JSON.stringify({ ...localStorage }),
      sessionStorage: JSON.stringify({ ...sessionStorage }),
      pinia: JSON.stringify(getActivePinia()?.state.value),
      route: JSON.stringify(router.currentRoute.value),
      location: window.location.href,
      historyState: JSON.stringify(window.history.state),
      console: JSON.stringify(consoleCalls),
    }
    for (const [place, content] of Object.entries(everywhere)) {
      expect(content, place).not.toContain(SECRET)
    }
    expect(localStorage.length + sessionStorage.length, 'nothing is written to browser storage at all').toBe(0)

    wrapper.unmount()
  })

  it('drops the value when the page is left', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await toggle(wrapper)
    expect(document.body.textContent).toContain(SECRET)

    wrapper.unmount()

    expect(document.body.textContent).not.toContain(SECRET)
  })

  it('drops the value when the page moves to another case', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await toggle(wrapper)

    await wrapper.setProps({ kase: caseResponse({ punishmentIdentifier: 'p-0002' }) })

    expect(wrapper.text()).not.toContain(SECRET)
    expect(wrapper.get('[data-testid="ban-id-value"]').attributes('data-revealed')).toBe('false')

    wrapper.unmount()
  })

  it('does not show a value that arrives after the page moved to another case', async () => {
    let release: (value: unknown) => void = () => undefined
    const revealBanId = vi.fn(() => new Promise((resolve) => (release = resolve)))
    const { mount } = setup({ extra: { revealBanId } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="ban-id-toggle"]').trigger('click')
    await wrapper.setProps({ kase: caseResponse({ punishmentIdentifier: 'p-0002' }) })
    release(ok(SECRET))
    await flushPromises()

    expect(wrapper.text()).not.toContain(SECRET)

    wrapper.unmount()
  })
})

describe('BanIdPanel: leak', () => {
  it('marks the Ban ID leaked with the chosen reason, hides the value and tells the page', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()
    await toggle(wrapper)

    await wrapper.get('[data-testid="ban-id-leak"]').trigger('click')
    await flushPromises()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'POSTED_PUBLIC')
    await wrapper.get('[data-testid="leak-confirm"]').trigger('click')
    await flushPromises()

    expect(api.markBanIdLeaked).toHaveBeenCalledWith('p-0001', 'POSTED_PUBLIC')
    expect(wrapper.emitted('changed')?.[0]).toEqual(['Ban ID marked as leaked.'])
    expect(wrapper.text()).not.toContain(SECRET)

    wrapper.unmount()
  })

  it('needs a reason before the confirm button works', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="ban-id-leak"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="leak-confirm"]').attributes('disabled')).toBeDefined()
    expect(api.markBanIdLeaked).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('keeps the dialog open with the server message when marking fails', async () => {
    const markBanIdLeaked = vi.fn(async () => fail('unknown punishment', 404, 'NOT_FOUND'))
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], extra: { markBanIdLeaked } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="ban-id-leak"]').trigger('click')
    await flushPromises()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'OTHER')
    await wrapper.get('[data-testid="leak-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="leak-error"]').text()).toBe('unknown punishment')
    expect(wrapper.emitted('changed')).toBeUndefined()

    wrapper.unmount()
  })

  it('shows the leak state and does not offer to mark it twice', async () => {
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], kase: { banIdLeakedAt: 1700, banIdLeakReason: 'SHARED' } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="ban-id-leaked"]').text()).toBe('Marked leaked: Shared with others')
    expect(wrapper.get('[data-testid="ban-id-leak"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })
})

describe('BanIdPanel: reissue', () => {
  it('is not offered to a moderator', async () => {
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="ban-id-reissue"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('reissues for an admin with a mandatory reason', async () => {
    const { api, mount } = setup({ kase: { banIdLeakedAt: 1700, banIdLeakReason: 'POSTED_PUBLIC' } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="ban-id-reissue"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="reissue-confirm"]').attributes('disabled'), 'no reason yet').toBeDefined()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'NOT_PLAYERS_FAULT')
    await wrapper.get('[data-testid="reissue-confirm"]').trigger('click')
    await flushPromises()

    expect(api.reissueBanId).toHaveBeenCalledWith('p-0001', 'NOT_PLAYERS_FAULT')
    expect(wrapper.emitted('changed')?.[0]).toEqual(['Ban ID reissued.'])

    wrapper.unmount()
  })
})
