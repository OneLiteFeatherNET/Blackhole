import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { KickAccepted, KickResult, KickStatus } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import KickDialog from './KickDialog.vue'

const OWNER = 'ab'.repeat(64)
const NOW = Date.UTC(2026, 9, 25, 12, 0, 0)

function accepted(overrides: Partial<KickAccepted> = {}): KickAccepted {
  return { id: 'kick-1', kind: 'PLAYER', status: 'PENDING', deadlineAt: NOW + 5000, warnings: [], repeated: false, ...overrides }
}

function result(status: KickStatus): KickResult {
  return { id: 'kick-1', kind: 'PLAYER', scope: 'NETWORK', reason: 'spam', status, deliveredCount: status === 'DELIVERED' ? 1 : 0, createdAt: NOW, deadlineAt: NOW + 5000 }
}

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    kickPlayer: vi.fn(async () => ok(accepted())),
    kick: vi.fn(async () => ok(result('PENDING'))),
    sections: vi.fn(async () => ok([{ key: 'chat', displayName: 'Chat', servers: ['Lobby-1', 'lobby-*'], enabled: true }, { key: 'old', displayName: 'Old', servers: [], enabled: false }])),
    cloudnetCatalogue: vi.fn(async () => ok({ available: true, tasks: [], groups: [], services: [{ name: 'BedWars-1', running: true }], proxies: [] })),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(KickDialog, { api: mockApi(api), props: { open: true, owner: OWNER, name: 'Notch' } }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

async function typeReason(wrapper: Wrapper, text: string) {
  await wrapper.get('[data-testid="kick-reason"] textarea').setValue(text)
}

async function submit(wrapper: Wrapper) {
  await wrapper.get('[data-testid="confirm"]').trigger('click')
  await flushPromises()
}

// Only timers and the clock are faked: flushPromises and Vue's scheduler keep working.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], now: NOW })
})
afterEach(() => {
  vi.useRealTimers()
})

describe('KickDialog reason', () => {
  it('refuses an empty reason and sends nothing', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await submit(wrapper)

    expect(wrapper.text()).toContain('Enter a reason.')
    expect(api.kickPlayer, 'no request').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('refuses a reason that contains an IP address and sends nothing', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await typeReason(wrapper, 'came from 192.168.0.12')
    await submit(wrapper)

    expect(wrapper.text()).toContain('must not contain a UUID, IP address or Ban ID')
    expect(api.kickPlayer, 'no request').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('refuses a reason longer than 200 characters', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await typeReason(wrapper, 'x'.repeat(201))
    await submit(wrapper)

    expect(wrapper.text()).toContain('At most 200 characters.')
    expect(api.kickPlayer).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('KickDialog submit', () => {
  it('sends the kick with an Idempotency-Key and no target for the network', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await typeReason(wrapper, ' spam ')
    await submit(wrapper)

    expect(api.kickPlayer).toHaveBeenCalledTimes(1)
    const [request, key] = api.kickPlayer.mock.calls[0] as unknown as [Record<string, unknown>, string]
    expect(request).toEqual({ owner: OWNER, scope: 'NETWORK', target: null, reason: 'spam' })
    expect(key, 'key shape the backend accepts').toMatch(/^[A-Za-z0-9._:-]{1,80}$/)
    wrapper.unmount()
  })

  it('offers enabled sections and plain server names once a scope needs a target', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="scope-SECTION"]').trigger('click')
    await flushPromises()
    await chooseOption(wrapper.get('[data-testid="kick-target"] [role="combobox"]'), 'chat')
    await typeReason(wrapper, 'spam')
    await submit(wrapper)

    expect((api.kickPlayer.mock.calls[0] as unknown[])[0]).toEqual({ owner: OWNER, scope: 'SECTION', target: 'chat', reason: 'spam' })
    wrapper.unmount()
  })

  it('refuses a server kick without a target', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="scope-SERVER"]').trigger('click')
    await typeReason(wrapper, 'spam')
    await submit(wrapper)

    expect(wrapper.text()).toContain('Choose a target.')
    expect(api.kickPlayer).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows the warnings of the answer', async () => {
    const { mount } = setup({ kickPlayer: vi.fn(async () => ok(accepted({ warnings: ['The player is permanently banned there.'] }))) })
    const { wrapper } = await mount()

    await typeReason(wrapper, 'spam')
    await submit(wrapper)

    expect(wrapper.get('[data-testid="kick-warnings"]').text()).toContain('permanently banned')
    wrapper.unmount()
  })

  it('shows the error of a refused kick and keeps the dialog usable', async () => {
    const { mount } = setup({ kickPlayer: vi.fn(async () => fail('The target section is disabled', 409, 'CONFLICT')) })
    const { wrapper } = await mount()

    await typeReason(wrapper, 'spam')
    await submit(wrapper)

    expect(wrapper.get('[data-testid="error"]').text()).toBe('The target section is disabled')
    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled'), 'can retry').toBeUndefined()
    wrapper.unmount()
  })

  it('retries a failed request with the same key so it cannot kick twice', async () => {
    const kickPlayer = vi.fn().mockResolvedValueOnce(fail('network down', 0, 'NETWORK')).mockResolvedValueOnce(ok(accepted()))
    const { mount } = setup({ kickPlayer })
    const { wrapper } = await mount()

    await typeReason(wrapper, 'spam')
    await submit(wrapper)
    await submit(wrapper)

    expect(kickPlayer.mock.calls[1]![1]).toBe(kickPlayer.mock.calls[0]![1])
    wrapper.unmount()
  })
})

describe('KickDialog polling', () => {
  it('reads the kick every second and shows the chip once the status is settled', async () => {
    const read = vi.fn().mockResolvedValueOnce(ok(result('PENDING'))).mockResolvedValueOnce(ok(result('DELIVERED')))
    const { mount } = setup({ kick: read })
    const { wrapper } = await mount()

    await typeReason(wrapper, 'spam')
    await submit(wrapper)
    expect(read, 'nothing before the first second').not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1000)
    expect(wrapper.find('[data-testid="kick-outcome"]').exists(), 'still pending').toBe(false)
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(wrapper.get('[data-testid="kick-outcome"]').text()).toBe('Delivered')
    expect(read).toHaveBeenCalledWith('kick-1')
    wrapper.unmount()
  })

  it('stops reading at a settled status', async () => {
    const read = vi.fn(async () => ok(result('NOT_ON_TARGET')))
    const { mount } = setup({ kick: read })
    const { wrapper } = await mount()

    await typeReason(wrapper, 'spam')
    await submit(wrapper)
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(10_000)

    expect(read, 'one read only').toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="kick-outcome"]').text()).toBe('Not on that server')
    wrapper.unmount()
  })

  it('gives up one second after the deadline and says there is no answer yet', async () => {
    const read = vi.fn(async () => ok(result('PENDING')))
    const { mount } = setup({ kick: read })
    const { wrapper } = await mount()

    await typeReason(wrapper, 'spam')
    await submit(wrapper)
    await vi.advanceTimersByTimeAsync(10_000)

    expect(read).toHaveBeenCalledTimes(6)
    expect(wrapper.find('[data-testid="kick-timeout"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('stops reading when the dialog is closed', async () => {
    const read = vi.fn(async () => ok(result('PENDING')))
    const { mount } = setup({ kick: read })
    const { wrapper } = await mount()

    await typeReason(wrapper, 'spam')
    await submit(wrapper)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(5000)

    expect(read).not.toHaveBeenCalled()
  })
})
