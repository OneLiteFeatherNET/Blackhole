import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import KickPage from './KickPage.vue'

const KICK_ID = '44444444-4444-4444-8444-444444444444'

const kick = {
  id: KICK_ID,
  kind: 'MASS',
  scope: 'SECTION',
  target: 'lobby',
  reason: 'Maintenance window',
  actor: 'Admin Bo',
  createdAt: Date.UTC(2026, 9, 28, 12, 0),
  status: 'DELIVERED',
  deliveredCount: 2,
  deadlineAt: Date.UTC(2026, 9, 28, 12, 1),
}

function setup(path: string, roles = ['ADMIN'], overrides: Record<string, unknown> = {}) {
  const api = {
    sections: vi.fn(async () => ok([])),
    cloudnetCatalogue: vi.fn(async () => ok({ available: false, tasks: [], groups: [], services: [], proxies: [], reportedAt: null })),
    kick: vi.fn(async () => ok(kick)),
    kickMass: vi.fn(async () => ok({ id: KICK_ID, deadlineAt: 0 })),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(KickPage, { api: mockApi(api), roles, path }) }
}

describe('KickPage at /admin/kick', () => {
  it('shows the intro and the way to a new mass kick, with no form and no request', async () => {
    const { api, mount } = setup('/admin/kick')
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="mass-kick"]').exists(), 'no form on the page').toBe(false)
    expect(wrapper.get('[data-testid="mass-kick-new"]').attributes('href')).toBe('/admin/kick/new')
    expect(api.sections, 'nothing is read before the dialog opens').not.toHaveBeenCalled()
    expect(api.kickMass).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('KickPage at /admin/kick/new', () => {
  it('opens the mass-kick form over the page, reads the targets, and sends nothing until the typed confirmation', async () => {
    const { api, mount } = setup('/admin/kick/new')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="mass-kick"]').exists(), 'the form is in the dialog').toBe(true)
    expect(api.sections).toHaveBeenCalledTimes(1)
    expect(api.kickMass).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('shows nothing of the form to a moderator and reads nothing', async () => {
    const { api, mount } = setup('/admin/kick/new', ['MODERATOR'])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="mass-kick"]').exists()).toBe(false)
    expect(api.sections).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('KickPage at /admin/kick/:id', () => {
  it('loads the kick by its id and shows how it went', async () => {
    const { api, mount } = setup(`/admin/kick/${KICK_ID}`)
    const { wrapper } = await mount()
    await flushPromises()

    expect(api.kick).toHaveBeenCalledWith(KICK_ID)
    expect(wrapper.get('[data-testid="kick-detail"]').text()).toContain('Delivered')
    expect(wrapper.get('[data-testid="kick-detail"]').text()).toContain('Maintenance window')
    expect(wrapper.get('[data-testid="kick-detail"]').text()).toContain('lobby')
    expect(api.kickMass).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('shows the not-found text for an unknown kick id', async () => {
    const { mount } = setup(`/admin/kick/${KICK_ID}`, ['ADMIN'], {
      kick: vi.fn(async () => fail('no such kick', 404, 'NOT_FOUND')),
    })
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="kick-detail-missing"]').text()).toContain('No kick with this id')

    wrapper.unmount()
  })
})
