import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { GuardState } from '../api/types'
import { mockApi, mountWithApp, ok } from '../test/mount'
import { chooseOption } from '../test/select'
import JoinGuardView from './JoinGuardView.vue'

const NOW = Date.UTC(2030, 0, 3, 12, 0)

function state(overrides: Partial<GuardState> = {}): GuardState {
  return { manualLevel: 'OFF', automaticLevel: 'OFF', effectiveLevel: 'OFF', updatedAt: NOW, ...overrides }
}

function setup(initial: GuardState, overrides: Record<string, unknown> = {}) {
  const api = {
    joinGuard: vi.fn(async () => ok(initial)),
    setJoinGuardLevel: vi.fn(async (level: string) => ok(state({ manualLevel: level, effectiveLevel: level }))),
    ...overrides,
  }
  return { api, mount: (roles: string[]) => mountWithApp(JoinGuardView, { api: mockApi(api), roles, path: '/guard' }) }
}

describe('JoinGuardView effective level', () => {
  it('shows the unattended level as the source when it is stricter than the manual one', async () => {
    const { mount } = setup(state({ manualLevel: 'OFF', automaticLevel: 'STRICT', effectiveLevel: 'STRICT' }))
    const { wrapper } = await mount(['VIEWER'])

    expect(wrapper.get('[data-testid="effective-level"]').text()).toBe('Strict')
    expect(wrapper.get('[data-testid="effective-source"]').text()).toBe('Set by the unattended evaluator')
    wrapper.unmount()
  })

  it('shows the manual level as the source when the staff set it', async () => {
    const { mount } = setup(state({ manualLevel: 'LOCKDOWN', automaticLevel: 'OFF', effectiveLevel: 'LOCKDOWN' }))
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    expect(wrapper.get('[data-testid="effective-source"]').text()).toBe('Set manually')
    wrapper.unmount()
  })

  it('shows that nothing is in force when the effective level is off', async () => {
    const { mount } = setup(state())
    const { wrapper } = await mount(['VIEWER'])

    expect(wrapper.get('[data-testid="effective-source"]').text()).toBe('No level in force')
    wrapper.unmount()
  })
})

describe('JoinGuardView role gating', () => {
  it('offers no level selector to a viewer, who reads the state only', async () => {
    const { mount } = setup(state())
    const { wrapper } = await mount(['VIEWER'])

    expect(wrapper.find('[data-testid="guard-level"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="read-only"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it.each([
    ['moderator', ['MODERATOR', 'VIEWER']],
    ['admin', ['ADMIN', 'MODERATOR', 'VIEWER']],
  ])('offers the level selector to a %s', async (_label, roles) => {
    const { mount } = setup(state())
    const { wrapper } = await mount(roles)

    expect(wrapper.find('[data-testid="guard-level"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('JoinGuardView setting the level', () => {
  it('keeps the set button disabled until the level differs from the current one', async () => {
    const { mount } = setup(state({ manualLevel: 'STRICT', effectiveLevel: 'STRICT' }))
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    expect(wrapper.get('[data-testid="set-level"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('asks for confirmation and sends the level only once confirmed', async () => {
    const { api, mount } = setup(state())
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    await chooseOption(wrapper.get('[data-testid="guard-level"] [role="combobox"]'), 'LOCKDOWN')
    await wrapper.get('[data-testid="set-level"]').trigger('click')
    expect(api.setJoinGuardLevel, 'nothing is sent before the confirmation').not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="confirm-body"]').text()).toContain('Lockdown')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.setJoinGuardLevel).toHaveBeenCalledWith('LOCKDOWN')
    wrapper.unmount()
  })

  it('shows the refusal of the server', async () => {
    const { mount } = setup(state(), {
      setJoinGuardLevel: vi.fn(async () => ({ ok: false, error: { status: 409, message: 'Try again.', kind: 'CONFLICT' } })),
    })
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    await chooseOption(wrapper.get('[data-testid="guard-level"] [role="combobox"]'), 'STRICT')
    await wrapper.get('[data-testid="set-level"]').trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('Try again.')
    wrapper.unmount()
  })
})
