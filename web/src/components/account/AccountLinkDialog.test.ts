import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import AccountLinkDialog from './AccountLinkDialog.vue'

const NOW = Date.UTC(2026, 9, 23, 12, 0, 0)
const UUID = '11111111-1111-4111-8111-111111111111'

function setup(overrides: Record<string, unknown> = {}, roles?: string[]) {
  const api = {
    staffLinkStatus: vi.fn(async () => ok({ linked: false })),
    issueStaffLinkCode: vi.fn(async () => ok({ code: 'ABCD-EFGH', expiresAt: NOW + 5 * 60_000 })),
    unlinkOwnStaffLink: vi.fn(async () => ok(undefined)),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(AccountLinkDialog, { api: mockApi(api), props: { open: true }, roles }) }
}

const writeText = vi.fn(async (_text: string) => {})

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  vi.setSystemTime(NOW)
  writeText.mockClear()
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
})
afterEach(() => vi.useRealTimers())

describe('AccountLinkDialog: status', () => {
  it('says "Not linked" in words and with an icon', async () => {
    const { wrapper } = await setup().mount()

    const status = wrapper.get('[data-testid="link-status"]')
    expect(status.text(), 'status text').toBe('Not linked')
    expect(status.find('svg[aria-hidden="true"]').exists(), 'icon besides the colour').toBe(true)
    expect(wrapper.find('[data-testid="unlink"]').exists(), 'nothing to unlink').toBe(false)

    wrapper.unmount()
  })

  it('names the linked Minecraft account and offers to unlink', async () => {
    const { mount } = setup({ staffLinkStatus: vi.fn(async () => ok({ linked: true, minecraftUuid: UUID, linkedAt: NOW })) })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="link-status"]').text()).toContain(`Linked to Minecraft account ${UUID}`)
    expect(wrapper.find('[data-testid="unlink"]').exists(), 'unlink offered').toBe(true)

    wrapper.unmount()
  })

  it('lets a viewer link themselves', async () => {
    const { api, mount } = setup({}, ['VIEWER'])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="issue"]').trigger('click')
    await flushPromises()

    expect(api.issueStaffLinkCode, 'a viewer may issue a code').toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="code"]').text()).toBe('ABCD-EFGH')

    wrapper.unmount()
  })
})

describe('AccountLinkDialog: code', () => {
  async function issued() {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="issue"]').trigger('click')
    await flushPromises()
    return { api, wrapper }
  }

  it('shows the code, the in-game command and the time left', async () => {
    const { wrapper } = await issued()

    expect(wrapper.get('[data-testid="code"]').text()).toBe('ABCD-EFGH')
    expect(wrapper.get('[data-testid="command"]').text()).toBe('/blackhole link ABCD-EFGH')
    expect(wrapper.get('[data-testid="countdown"]').text()).toBe('Valid for 05:00')

    wrapper.unmount()
  })

  it('counts down with the clock and keeps the countdown out of the live region', async () => {
    const { wrapper } = await issued()

    vi.advanceTimersByTime(61_000)
    await flushPromises()

    const countdown = wrapper.get('[data-testid="countdown"]')
    expect(countdown.text()).toBe('Valid for 03:59')
    expect(countdown.attributes('aria-live'), 'ticking is not announced').toBe('off')
    expect(wrapper.get('[data-testid="announcement"]').text(), 'nothing announced while ticking').toBe('')

    wrapper.unmount()
  })

  it('clears the code at expiry, announces it once and offers a new code', async () => {
    const { wrapper } = await issued()

    vi.advanceTimersByTime(5 * 60_000)
    await flushPromises()

    expect(wrapper.find('[data-testid="code"]').exists(), 'code is gone').toBe(false)
    const announcement = wrapper.get('[data-testid="announcement"]')
    expect(announcement.text()).toBe('The code has expired.')
    expect(announcement.attributes('aria-live')).toBe('polite')
    expect(wrapper.get('[data-testid="issue"]').text()).toBe('Generate a new code')

    wrapper.unmount()
  })

  it('stops ticking after expiry', async () => {
    const { wrapper } = await issued()

    vi.advanceTimersByTime(5 * 60_000)

    expect(vi.getTimerCount(), 'no interval is left running').toBe(0)

    wrapper.unmount()
  })

  it('stops the timer when the dialog is unmounted', async () => {
    const { wrapper } = await issued()

    wrapper.unmount()

    expect(vi.getTimerCount(), 'no interval is left running').toBe(0)
  })

  it('copies the code and confirms politely', async () => {
    const { wrapper } = await issued()

    await wrapper.get('[data-testid="copy-code"]').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith('ABCD-EFGH')
    expect(wrapper.get('[data-testid="announcement"]').text()).toBe('Copied to the clipboard.')

    wrapper.unmount()
  })

  it('copies the whole command', async () => {
    const { wrapper } = await issued()

    await wrapper.get('[data-testid="copy-command"]').trigger('click')

    expect(writeText).toHaveBeenCalledWith('/blackhole link ABCD-EFGH')

    wrapper.unmount()
  })

  it('tells the user when copying is refused', async () => {
    const { wrapper } = await issued()
    writeText.mockRejectedValueOnce(new Error('denied'))

    await wrapper.get('[data-testid="copy-code"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="announcement"]').text()).toContain('Copying failed')

    wrapper.unmount()
  })

  it('shows the backend message when no code can be issued', async () => {
    const { mount } = setup({ issueStaffLinkCode: vi.fn(async () => fail('forbidden for service principals', 403)) })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="issue"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('forbidden for service principals')
    expect(wrapper.find('[data-testid="code"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('AccountLinkDialog: unlink', () => {
  const linked = { staffLinkStatus: vi.fn(async () => ok({ linked: true, minecraftUuid: UUID, linkedAt: NOW })) }

  it('asks before removing and removes nothing on cancel', async () => {
    const { api, mount } = setup(linked)
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="unlink"]').trigger('click')
    expect(wrapper.text()).toContain('Remove the link?')
    await wrapper.get('[data-testid="unlink-cancel"]').trigger('click')

    expect(api.unlinkOwnStaffLink, 'cancel sends nothing').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('removes the link after confirmation and reloads the status', async () => {
    const status = vi.fn().mockResolvedValueOnce(ok({ linked: true, minecraftUuid: UUID, linkedAt: NOW })).mockResolvedValueOnce(ok({ linked: false }))
    const { api, mount } = setup({ staffLinkStatus: status })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="unlink"]').trigger('click')
    await wrapper.get('[data-testid="unlink-confirm"]').trigger('click')
    await flushPromises()

    expect(api.unlinkOwnStaffLink).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="link-status"]').text()).toBe('Not linked')
    expect(wrapper.get('[data-testid="announcement"]').text()).toBe('Link removed.')

    wrapper.unmount()
  })

  it('returns focus to the unlink button when the confirmation is cancelled', async () => {
    const { mount } = setup(linked)
    const { wrapper } = await mount()
    const button = wrapper.get('[data-testid="unlink"]')
    ;(button.element as HTMLElement).focus()

    await button.trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="unlink-cancel"]').trigger('click')
    await flushPromises()

    expect(document.activeElement, 'focus is back on the opener').toBe(button.element)

    wrapper.unmount()
  })
})

describe('AccountLinkDialog: dialog behaviour', () => {
  it('is a labelled modal dialog that closes on Escape', async () => {
    const { wrapper } = await setup().mount()

    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(dialog.attributes('aria-labelledby'), 'has a label').toBeTruthy()
    await dialog.trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('close')).toHaveLength(1)

    wrapper.unmount()
  })

  it('keeps Tab inside the dialog', async () => {
    const { wrapper } = await setup().mount()
    const buttons = wrapper.findAll('[role="dialog"] button')
    const last = buttons[buttons.length - 1]!.element as HTMLElement
    last.focus()

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Tab' })

    expect(document.activeElement, 'wraps to the first control').toBe(buttons[0]!.element)

    wrapper.unmount()
  })
})
