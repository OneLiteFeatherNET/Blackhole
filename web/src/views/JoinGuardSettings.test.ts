import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { GuardState } from '../api/types'
import { windowInZone } from '../components/guard/guardSettings'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import { chooseOption } from '../test/select'
import JoinGuardView from './JoinGuardView.vue'

const NOW = Date.UTC(2030, 0, 3, 12, 0)
const NEXT_START = Date.UTC(2030, 0, 4, 0, 0)
const NEXT_END = Date.UTC(2030, 0, 4, 6, 0)

function state(): GuardState {
  return { manualLevel: 'OFF', automaticLevel: 'OFF', effectiveLevel: 'OFF', updatedAt: NOW }
}

interface Settings {
  unattendedLevel: string
  absenceThresholdMinutes: number
  nightWindowEnabled: boolean
  nightWindowStart: string
  nightWindowEnd: string
  timeZone: string
  updatedAt: number
  updatedByOid: string | null
  nextNightWindowStart: number | null
  nextNightWindowEnd: number | null
}

function settings(overrides: Partial<Settings> = {}): Settings {
  return {
    unattendedLevel: 'STRICT',
    absenceThresholdMinutes: 20,
    nightWindowEnabled: true,
    nightWindowStart: '01:00',
    nightWindowEnd: '07:00',
    timeZone: 'Europe/Berlin',
    updatedAt: NOW,
    updatedByOid: null,
    nextNightWindowStart: NEXT_START,
    nextNightWindowEnd: NEXT_END,
    ...overrides,
  }
}

function setup(initial: Settings, overrides: Record<string, unknown> = {}) {
  const api = {
    joinGuard: vi.fn(async () => ok(state())),
    joinGuardSettings: vi.fn(async () => ok(initial)),
    setJoinGuardSettings: vi.fn(async (body: Settings) => ok({ ...body, updatedAt: NOW, updatedByOid: null, nextNightWindowStart: NEXT_START, nextNightWindowEnd: NEXT_END })),
    ...overrides,
  }
  return { api, mount: (roles: string[]) => mountWithApp(JoinGuardView, { api: mockApi(api), roles, path: '/guard' }) }
}

const ADMIN = ['ADMIN', 'MODERATOR', 'VIEWER']

describe('JoinGuardView settings section, roles', () => {
  it('lets an administrator edit the settings and save them', async () => {
    const { mount } = setup(settings())
    const { wrapper } = await mount(ADMIN)
    await flushPromises()

    expect(wrapper.find('[data-testid="settings-form"]').exists(), 'an admin sees the form').toBe(true)
    expect(wrapper.find('[data-testid="settings-save"]').exists(), 'an admin sees the save action').toBe(true)
    expect(wrapper.find('[data-testid="settings-read-only"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each([
    ['a moderator', ['MODERATOR', 'VIEWER']],
    ['a viewer', ['VIEWER']],
  ])('shows %s the values read-only and no form', async (_label, roles) => {
    const { mount } = setup(settings())
    const { wrapper } = await mount(roles)
    await flushPromises()

    expect(wrapper.find('[data-testid="settings-form"]').exists(), 'no input control for a non-admin').toBe(false)
    expect(wrapper.get('[data-testid="settings-read-only"]').text()).toContain('20 minutes')
    wrapper.unmount()
  })
})

describe('JoinGuardView next night window', () => {
  it('shows the next window in the viewer zone and names the configured zone', async () => {
    const { mount } = setup(settings())
    const { wrapper } = await mount(['VIEWER'])
    await flushPromises()

    const text = wrapper.get('[data-testid="settings-next-window"]').text()
    const shown = windowInZone(NEXT_START, NEXT_END, 'en', undefined)
    expect(text, 'the window is rendered in the viewer zone').toContain(`${shown.start} to ${shown.end}`)
    expect(text, 'the configured zone is named').toContain('Europe/Berlin')
    wrapper.unmount()
  })

  it('says that no night window applies when the window is disabled, and shows no times', async () => {
    const { mount } = setup(settings({ nightWindowEnabled: false, nextNightWindowStart: null, nextNightWindowEnd: null }))
    const { wrapper } = await mount(['VIEWER'])
    await flushPromises()

    const text = wrapper.get('[data-testid="settings-next-window"]').text()
    expect(text).toBe('No night window applies: the window is disabled.')
    wrapper.unmount()
  })
})

describe('JoinGuardView night window fields', () => {
  it('labels the night window start field with a short label and no supporting text', async () => {
    const { mount } = setup(settings())
    const { wrapper } = await mount(ADMIN)
    await flushPromises()

    const start = wrapper.get('[data-testid="settings-night-start"]')
    expect(start.get('label').text(), 'the start label stays short').toBe('Night window start *')
    expect(start.find('p').exists(), 'the start field has no supporting text').toBe(false)
    wrapper.unmount()
  })

  it('labels the night window end field short and puts the exclusion in its supporting text', async () => {
    const { mount } = setup(settings())
    const { wrapper } = await mount(ADMIN)
    await flushPromises()

    const end = wrapper.get('[data-testid="settings-night-end"]')
    expect(end.get('label').text(), 'the end label stays short').toBe('Night window end *')
    expect(end.get('p').text(), 'the exclusion is explained under the field').toBe('This time is not included in the night window.')
    wrapper.unmount()
  })
})

describe('JoinGuardView saving the settings', () => {
  it('saves a change of the threshold without asking for confirmation', async () => {
    const { api, mount } = setup(settings())
    const { wrapper } = await mount(ADMIN)
    await flushPromises()

    await wrapper.get('[data-testid="settings-threshold"] input').setValue('30')
    await wrapper.get('[data-testid="settings-save"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="settings-confirm-body"]').exists(), 'no dialog for a threshold change').toBe(false)
    expect(api.setJoinGuardSettings).toHaveBeenCalledWith(expect.objectContaining({ absenceThresholdMinutes: 30, unattendedLevel: 'STRICT' }))
    wrapper.unmount()
  })

  it('asks for confirmation before LOCKDOWN is sent as the unattended level', async () => {
    const { api, mount } = setup(settings())
    const { wrapper } = await mount(ADMIN)
    await flushPromises()

    await chooseOption(wrapper.get('[data-testid="settings-level"] [role="combobox"]'), 'LOCKDOWN')
    await wrapper.get('[data-testid="settings-save"]').trigger('click')
    await flushPromises()

    expect(api.setJoinGuardSettings, 'nothing is sent before the confirmation').not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="settings-confirm-body"]').text()).toContain('Lockdown')

    await wrapper.get('[data-testid="settings-confirm-accept"]').trigger('click')
    await flushPromises()

    expect(api.setJoinGuardSettings).toHaveBeenCalledWith(expect.objectContaining({ unattendedLevel: 'LOCKDOWN' }))
    wrapper.unmount()
  })

  it('keeps the save action disabled for a threshold outside the bounds, and names the range', async () => {
    const { api, mount } = setup(settings())
    const { wrapper } = await mount(ADMIN)
    await flushPromises()

    await wrapper.get('[data-testid="settings-threshold"] input').setValue('300')

    expect(wrapper.get('[data-testid="settings-threshold"]').text()).toContain('240')
    expect(wrapper.get('[data-testid="settings-save"]').attributes('disabled')).toBeDefined()
    expect(api.setJoinGuardSettings).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows the refusal of the server in the form', async () => {
    const { mount } = setup(settings(), {
      setJoinGuardSettings: vi.fn(async () => fail('The threshold is outside its bounds.', 400, 'VALIDATION')),
    })
    const { wrapper } = await mount(ADMIN)
    await flushPromises()

    await wrapper.get('[data-testid="settings-threshold"] input').setValue('30')
    await wrapper.get('[data-testid="settings-save"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="settings-error"]').text()).toContain('The threshold is outside its bounds.')
    wrapper.unmount()
  })
})
