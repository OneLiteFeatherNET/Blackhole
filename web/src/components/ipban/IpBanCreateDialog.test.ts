import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import type { IpBan } from '../../api/types'
import IpBanCreateDialog from './IpBanCreateDialog.vue'

const DAY_MS = 86_400_000
const NOW = Date.UTC(2026, 9, 29, 12, 0, 0)

const created: IpBan = { id: 'b1', range: '203.0.113.7', reason: 'Ban evasion', source: 'PANEL', createdAt: NOW, warnings: [] }

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

/** Moderators by default: the stricter role, so the duration cap applies unless a test asks for ADMIN. */
function mountDialog(createIpBan = vi.fn(async () => ok(created)), roles: string[] = ['MODERATOR']) {
  return mountWithApp(IpBanCreateDialog, { api: mockApi({ createIpBan }), props: { open: true }, roles })
}

/** Types into the text field with the given test id. */
async function typeInto(wrapper: Awaited<ReturnType<typeof mountDialog>>['wrapper'], testId: string, value: string): Promise<void> {
  await wrapper.get(`[data-testid="${testId}"] input`).setValue(value)
  await flushPromises()
}

async function fillValid(wrapper: Awaited<ReturnType<typeof mountDialog>>['wrapper'], duration = '7d'): Promise<void> {
  await typeInto(wrapper, 'address', '203.0.113.7')
  await typeInto(wrapper, 'reason', 'Ban evasion')
  await typeInto(wrapper, 'duration', duration)
}

describe('IpBanCreateDialog: role gating', () => {
  it('does not offer the permanent option to a moderator', async () => {
    const { wrapper } = await mountDialog(vi.fn(), ['MODERATOR'])

    expect(wrapper.find('[data-testid="permanent"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('offers the permanent option to an administrator', async () => {
    const { wrapper } = await mountDialog(vi.fn(), ['ADMIN'])

    expect(wrapper.find('[data-testid="permanent"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('refuses a moderator duration above the 30 day cap and keeps submit disabled', async () => {
    const { wrapper } = await mountDialog(vi.fn(), ['MODERATOR'])
    await fillValid(wrapper, '31d')

    expect(wrapper.get('[data-testid="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('A moderator may ban for at most 30 days.')

    wrapper.unmount()
  })
})

describe('IpBanCreateDialog: address validation', () => {
  it.each(['203.0.113.7', '2001:db8::1', '203.0.113.0/24', '2001:db8::/32'])('accepts %s and enables submit', async (address) => {
    const { wrapper } = await mountDialog()
    await fillValid(wrapper)
    await typeInto(wrapper, 'address', address)

    expect(wrapper.get('[data-testid="submit"]').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })

  it('refuses a malformed address in the form and keeps submit disabled', async () => {
    const { wrapper } = await mountDialog()
    await fillValid(wrapper)
    await typeInto(wrapper, 'address', '999.1.1.1')

    expect(wrapper.text()).toContain('Enter one IPv4 or IPv6 address, or a CIDR range')
    expect(wrapper.get('[data-testid="submit"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('refuses a range wider than the API allows', async () => {
    const { wrapper } = await mountDialog()
    await fillValid(wrapper)
    await typeInto(wrapper, 'address', '203.0.0.0/8')

    expect(wrapper.text()).toContain('A range this wide is refused.')

    wrapper.unmount()
  })
})

describe('IpBanCreateDialog: submit', () => {
  it('sends a temporary ban with the expiry counted from now', async () => {
    const createIpBan = vi.fn(async () => ok(created))
    const { wrapper } = await mountDialog(createIpBan)
    await fillValid(wrapper, '7d')

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(createIpBan).toHaveBeenCalledWith({ address: '203.0.113.7', reason: 'Ban evasion', expiresAt: NOW + 7 * DAY_MS })

    wrapper.unmount()
  })

  it('sends a permanent ban without an expiry when an administrator ticks permanent', async () => {
    const createIpBan = vi.fn(async () => ok(created))
    const { wrapper } = await mountDialog(createIpBan, ['ADMIN'])
    await typeInto(wrapper, 'address', '2001:db8::/32')
    await typeInto(wrapper, 'reason', 'Botnet')
    await wrapper.get('[data-testid="permanent"] input').setValue(true)
    await flushPromises()

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(createIpBan).toHaveBeenCalledWith({ address: '2001:db8::/32', reason: 'Botnet', expiresAt: null })

    wrapper.unmount()
  })

  it('shows the server rule for a protected address and stays open', async () => {
    const createIpBan = vi.fn(async () => ({ ok: false as const, error: { status: 422, message: 'server text', kind: 'UNKNOWN' as const, code: 'IPBAN_PROTECTED' } }))
    const { wrapper } = await mountDialog(createIpBan)
    await fillValid(wrapper)

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('This address is protected and cannot be banned.')
    expect(wrapper.emitted('done')).toBeUndefined()

    wrapper.unmount()
  })

  it('shows the server text for a refusal without a known code', async () => {
    const createIpBan = vi.fn(async () => fail('the ban overlaps a protected network', 422))
    const { wrapper } = await mountDialog(createIpBan)
    await fillValid(wrapper)

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('the ban overlaps a protected network')

    wrapper.unmount()
  })

  it('reports the warnings of a ban that is inside a wider active one', async () => {
    const createIpBan = vi.fn(async () => ok({ ...created, warnings: ['inside 203.0.0.0/16'] }))
    const { wrapper } = await mountDialog(createIpBan)
    await fillValid(wrapper)

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('done')).toEqual([['The IP ban was stored. Warning: inside 203.0.0.0/16']])

    wrapper.unmount()
  })
})
