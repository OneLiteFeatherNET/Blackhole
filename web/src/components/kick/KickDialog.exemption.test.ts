import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { KickAccepted, KickResult } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import KickDialog from './KickDialog.vue'

// The refusal of a protected target and the ADMIN's override, in the kick dialog.

const OWNER = 'ab'.repeat(64)
const NOW = Date.UTC(2026, 9, 25, 12, 0, 0)
const REFUSAL = 'The player is exempt from punishment.'

const accepted: KickAccepted = { id: 'kick-1', kind: 'PLAYER', status: 'PENDING', deadlineAt: NOW + 5000, warnings: [], repeated: false }
const pending: KickResult = { id: 'kick-1', kind: 'PLAYER', scope: 'NETWORK', reason: 'spam', status: 'PENDING', deliveredCount: 0, createdAt: NOW, deadlineAt: NOW + 5000 }

function setup() {
  const api = {
    // Refused without an override; accepted with one.
    kickPlayer: vi.fn(async (request: { exemptionOverride?: { justification: string } }) =>
      request.exemptionOverride ? ok(accepted) : fail(REFUSAL, 409, 'TARGET_EXEMPT'),
    ),
    kick: vi.fn(async () => ok(pending)),
  }
  return { api, mount: (roles: string[]) => mountWithApp(KickDialog, { api: mockApi(api), props: { open: true, owner: OWNER, name: 'Notch' }, roles }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

async function kickWithReason(wrapper: Wrapper) {
  await wrapper.get('[data-testid="kick-reason"] textarea').setValue('spam')
  await wrapper.get('[data-testid="confirm"]').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], now: NOW })
})
afterEach(() => {
  vi.useRealTimers()
})

describe('KickDialog with a protected target for a moderator', () => {
  it('shows why the kick was refused and that only an administrator can decide', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    await kickWithReason(wrapper)

    expect(wrapper.get('[data-testid="exemption-refusal"]').text()).toContain(REFUSAL)
    expect(wrapper.text()).toContain('Only an administrator')
    expect(api.kickPlayer, 'one attempt, no override').toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('offers no override field and keeps the kick action disabled', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    await kickWithReason(wrapper)

    expect(wrapper.find('[data-testid="exemption-justification"]').exists(), 'no field').toBe(false)
    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled'), 'kick stays disabled').toBeDefined()
    wrapper.unmount()
  })
})

describe('KickDialog with a protected target for an administrator', () => {
  it('kicks with the justification and the same idempotency key as the refused attempt', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])
    await kickWithReason(wrapper)

    await wrapper.get('[data-testid="exemption-justification"] input').setValue('Owner agreed in the forum')
    await wrapper.get('[data-testid="exemption-override"]').trigger('click')
    await flushPromises()

    expect(api.kickPlayer).toHaveBeenCalledTimes(2)
    const [first, firstKey] = api.kickPlayer.mock.calls[0] as unknown as [Record<string, unknown>, string]
    const [second, secondKey] = api.kickPlayer.mock.calls[1] as unknown as [Record<string, unknown>, string]
    expect(first, 'the first attempt carries no override').not.toHaveProperty('exemptionOverride')
    expect(second).toEqual(expect.objectContaining({ exemptionOverride: { justification: 'Owner agreed in the forum' } }))
    expect(secondKey, 'a retry keeps the idempotency key').toBe(firstKey)
    wrapper.unmount()
  })

  it('sends no override while the justification is empty', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])
    await kickWithReason(wrapper)

    await wrapper.get('[data-testid="exemption-override"]').trigger('click')
    await flushPromises()

    expect(api.kickPlayer, 'only the first, refused attempt').toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('Enter a justification.')
    wrapper.unmount()
  })
})
