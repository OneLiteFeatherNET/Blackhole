import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { KickAccepted, KickResult } from '../../api/types'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import MassKickForm from './MassKickForm.vue'

const NOW = Date.UTC(2026, 9, 25, 12, 0, 0)

const accepted: KickAccepted = { id: 'mass-1', kind: 'MASS', status: 'PENDING', deadlineAt: NOW + 5000, warnings: [], repeated: false }
const delivered: KickResult = { id: 'mass-1', kind: 'MASS', scope: 'SERVER', target: 'Lobby-1', reason: 'restart', status: 'DELIVERED', deliveredCount: 2, createdAt: NOW, deadlineAt: NOW + 5000 }

function setup(roles: string[] = ['ADMIN']) {
  const api = {
    kickMass: vi.fn(async () => ok(accepted)),
    kick: vi.fn(async () => ok(delivered)),
    sections: vi.fn(async () => ok([{ key: 'chat', displayName: 'Chat', servers: ['Lobby-1'], enabled: true }])),
    cloudnetCatalogue: vi.fn(async () => ok({ available: false, tasks: [], groups: [], services: [], proxies: [] })),
  }
  return { api, mount: () => mountWithApp(MassKickForm, { api: mockApi(api), roles }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

async function pickTarget(wrapper: Wrapper, value: string) {
  await chooseOption(wrapper.get('[data-testid="mass-target"] [role="combobox"]'), value)
  await flushPromises()
}

const submitButton = (wrapper: Wrapper) => wrapper.get('[data-testid="mass-submit"]')

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], now: NOW })
})
afterEach(() => {
  vi.useRealTimers()
})

describe('MassKickForm', () => {
  it('is not rendered for a moderator and asks for nothing', async () => {
    const { api, mount } = setup(['MODERATOR'])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="mass-kick"]').exists()).toBe(false)
    expect(api.sections, 'no request').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the submit disabled until a target is chosen', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('keeps the submit disabled while the typed name is not exactly the target', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await pickTarget(wrapper, 'Lobby-1')
    await wrapper.get('[data-testid="mass-confirm"] input').setValue('lobby-1')

    expect(submitButton(wrapper).attributes('disabled'), 'case differs').toBeDefined()
    wrapper.unmount()
  })

  it('enables the submit when the typed name is exactly the target', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await pickTarget(wrapper, 'Lobby-1')
    await wrapper.get('[data-testid="mass-confirm"] input').setValue('Lobby-1')

    expect(submitButton(wrapper).attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('shows no second approval step', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await pickTarget(wrapper, 'Lobby-1')
    await wrapper.get('[data-testid="mass-confirm"] input').setValue('Lobby-1')

    expect(wrapper.find('[role="dialog"]').exists(), 'no confirmation dialog').toBe(false)
    expect(wrapper.text().toLowerCase(), 'no approval wording').not.toContain('approv')
    wrapper.unmount()
  })

  it('forgets the typed name when the target changes', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await pickTarget(wrapper, 'Lobby-1')
    await wrapper.get('[data-testid="mass-confirm"] input').setValue('Lobby-1')
    await wrapper.get('[data-testid="scope-SECTION"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('[data-testid="mass-confirm"] input').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('sends one mass kick with an Idempotency-Key and shows how many proxies delivered it', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await pickTarget(wrapper, 'Lobby-1')
    await wrapper.get('[data-testid="mass-reason"] textarea').setValue('restart')
    await wrapper.get('[data-testid="mass-confirm"] input').setValue('Lobby-1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    const [request, key] = api.kickMass.mock.calls[0] as unknown as [Record<string, unknown>, string]
    expect(request).toEqual({ scope: 'SERVER', target: 'Lobby-1', reason: 'restart' })
    expect(key).toMatch(/^[A-Za-z0-9._:-]{1,80}$/)
    expect(wrapper.get('[data-testid="mass-delivered"]').text()).toBe('Status: Delivered. Delivered by 2 proxies.')
    wrapper.unmount()
  })
})
