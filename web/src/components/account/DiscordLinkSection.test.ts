import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import DiscordLinkSection from './DiscordLinkSection.vue'

const NOW = Date.UTC(2026, 9, 23, 12, 0, 0)

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    discordLinkStatus: vi.fn(async () => ok({ linked: false })),
    issueDiscordLinkCode: vi.fn(async () => ok({ code: 'ABCD-EFGH', expiresAt: NOW + 5 * 60_000 })),
    unlinkOwnDiscordLink: vi.fn(async () => ok(undefined)),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(DiscordLinkSection, { api: mockApi(api) }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']
const byId = (wrapper: Wrapper, id: string) => wrapper.get(`[data-testid="${id}"]`)

const writeText = vi.fn(async (_text: string) => {})

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  vi.setSystemTime(NOW)
  writeText.mockClear()
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
})
afterEach(() => vi.useRealTimers())

describe('DiscordLinkSection: ticket channel access', () => {
  it('tells staff that only linked accounts can open the ticket channels', async () => {
    const { wrapper } = await setup().mount()

    expect(byId(wrapper, 'discord-access-hint').text()).toContain('Only staff with a linked Discord account')
    expect(byId(wrapper, 'discord-access-hint').text()).toContain('/link')

    wrapper.unmount()
  })
})

describe('DiscordLinkSection: status', () => {
  it('says "Discord not linked" in words and with an icon', async () => {
    const { wrapper } = await setup().mount()

    const status = byId(wrapper, 'discord-status')
    expect(status.text(), 'status text').toBe('Discord not linked')
    expect(status.find('svg[aria-hidden="true"]').exists(), 'icon besides the colour').toBe(true)
    expect(wrapper.find('[data-testid="discord-unlink"]').exists(), 'nothing to unlink').toBe(false)

    wrapper.unmount()
  })

  it('shows a linked account and offers to unlink', async () => {
    const { mount } = setup({ discordLinkStatus: vi.fn(async () => ok({ linked: true, linkedAt: NOW })) })
    const { wrapper } = await mount()

    expect(byId(wrapper, 'discord-status').text()).toContain('Discord account linked')
    expect(wrapper.find('[data-testid="discord-unlink"]').exists(), 'unlink offered').toBe(true)

    wrapper.unmount()
  })

  it('shows the backend message when the status cannot be read', async () => {
    const { mount } = setup({ discordLinkStatus: vi.fn(async () => fail('boom', 500)) })
    const { wrapper } = await mount()

    expect(byId(wrapper, 'discord-error').text()).toBe('boom')

    wrapper.unmount()
  })
})

describe('DiscordLinkSection: code', () => {
  async function issued() {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await byId(wrapper, 'discord-issue').trigger('click')
    await flushPromises()
    return { api, wrapper }
  }

  it('shows the code, the /link command and the time left', async () => {
    const { wrapper } = await issued()

    expect(byId(wrapper, 'discord-code').text()).toBe('ABCD-EFGH')
    expect(byId(wrapper, 'discord-command').text()).toBe('/link ABCD-EFGH')
    expect(byId(wrapper, 'discord-countdown').text()).toBe('Valid for 05:00')

    wrapper.unmount()
  })

  it('counts down with the clock and keeps the countdown out of the live region', async () => {
    const { wrapper } = await issued()

    vi.advanceTimersByTime(61_000)
    await flushPromises()

    const countdown = byId(wrapper, 'discord-countdown')
    expect(countdown.text()).toBe('Valid for 03:59')
    expect(countdown.attributes('aria-live'), 'ticking is not announced').toBe('off')
    expect(byId(wrapper, 'discord-announcement').text(), 'nothing announced while ticking').toBe('')

    wrapper.unmount()
  })

  it('clears the code at expiry, announces it once and offers a new code', async () => {
    const { wrapper } = await issued()

    vi.advanceTimersByTime(5 * 60_000)
    await flushPromises()

    expect(wrapper.find('[data-testid="discord-code"]').exists(), 'code is gone').toBe(false)
    const announcement = byId(wrapper, 'discord-announcement')
    expect(announcement.text()).toBe('The code has expired.')
    expect(announcement.attributes('aria-live')).toBe('polite')
    expect(byId(wrapper, 'discord-issue').text()).toBe('Generate a new code')

    wrapper.unmount()
  })

  it('stops the timer when the section is unmounted', async () => {
    const { wrapper } = await issued()

    wrapper.unmount()

    expect(vi.getTimerCount(), 'no interval is left running').toBe(0)
  })

  it('copies the code and confirms politely', async () => {
    const { wrapper } = await issued()

    await byId(wrapper, 'discord-copy-code').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('ABCD-EFGH')
    expect(byId(wrapper, 'discord-announcement').text()).toBe('Copied to the clipboard.')

    wrapper.unmount()
  })

  it('copies the whole command', async () => {
    const { wrapper } = await issued()

    await byId(wrapper, 'discord-copy-command').trigger('click')

    expect(writeText).toHaveBeenCalledWith('/link ABCD-EFGH')

    wrapper.unmount()
  })

  it('tells the user when copying is refused', async () => {
    const { wrapper } = await issued()
    writeText.mockRejectedValueOnce(new Error('denied'))

    await byId(wrapper, 'discord-copy-code').trigger('click')
    await flushPromises()

    expect(byId(wrapper, 'discord-announcement').text()).toContain('Copying failed')

    wrapper.unmount()
  })

  it('shows the backend message when no code can be issued', async () => {
    const { mount } = setup({ issueDiscordLinkCode: vi.fn(async () => fail('forbidden for service principals', 403)) })
    const { wrapper } = await mount()

    await byId(wrapper, 'discord-issue').trigger('click')
    await flushPromises()

    expect(byId(wrapper, 'discord-error').text()).toBe('forbidden for service principals')
    expect(wrapper.find('[data-testid="discord-code"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('DiscordLinkSection: unlink', () => {
  const linked = () => ({ discordLinkStatus: vi.fn(async () => ok({ linked: true, linkedAt: NOW })) })

  it('asks before removing and removes nothing on cancel', async () => {
    const { api, mount } = setup(linked())
    const { wrapper } = await mount()

    await byId(wrapper, 'discord-unlink').trigger('click')
    expect(wrapper.text()).toContain('Remove the Discord link?')
    await byId(wrapper, 'discord-unlink-cancel').trigger('click')

    expect(api.unlinkOwnDiscordLink, 'cancel sends nothing').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('removes the link after confirmation and reloads the status', async () => {
    const status = vi.fn().mockResolvedValueOnce(ok({ linked: true, linkedAt: NOW })).mockResolvedValueOnce(ok({ linked: false }))
    const { api, mount } = setup({ discordLinkStatus: status })
    const { wrapper } = await mount()

    await byId(wrapper, 'discord-unlink').trigger('click')
    await byId(wrapper, 'discord-unlink-confirm').trigger('click')
    await flushPromises()

    expect(api.unlinkOwnDiscordLink).toHaveBeenCalledTimes(1)
    expect(byId(wrapper, 'discord-status').text()).toBe('Discord not linked')
    expect(byId(wrapper, 'discord-announcement').text()).toBe('Discord link removed.')

    wrapper.unmount()
  })

  it('shows the backend message when removing fails', async () => {
    const { mount } = setup({ ...linked(), unlinkOwnDiscordLink: vi.fn(async () => fail('nope', 500)) })
    const { wrapper } = await mount()

    await byId(wrapper, 'discord-unlink').trigger('click')
    await byId(wrapper, 'discord-unlink-confirm').trigger('click')
    await flushPromises()

    expect(byId(wrapper, 'discord-error').text()).toBe('nope')

    wrapper.unmount()
  })
})
