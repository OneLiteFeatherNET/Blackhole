import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption, listOptions } from '../../test/select'
import AreaBanCreateDialog from './AreaBanCreateDialog.vue'

const SEVEN_DAYS = 7 * 24 * 60 * 60
const THREE_DAYS = 3 * 24 * 60 * 60

function setup(roles: string[], overrides: Record<string, unknown> = {}) {
  const api = {
    createAreaBan: vi.fn(async () => ok({ id: 'b-1' })),
    ...overrides,
  }
  return {
    api,
    mount: () => mountWithApp(AreaBanCreateDialog, { api: mockApi(api), roles, props: { open: true } }),
  }
}

describe('AreaBanCreateDialog for a moderator', () => {
  it('offers only the ASN kind', async () => {
    const { mount } = setup(['MODERATOR', 'VIEWER'])
    const { wrapper } = await mount()

    const kinds = await listOptions(wrapper.get('[data-testid="area-kind"] [role="combobox"]'))

    expect(kinds.map((kind) => kind.value)).toEqual(['ASN'])
    wrapper.unmount()
  })

  it('offers no permanent duration and nothing above seven days', async () => {
    const { mount } = setup(['MODERATOR', 'VIEWER'])
    const { wrapper } = await mount()

    const durations = await listOptions(wrapper.get('[data-testid="area-duration"] [role="combobox"]'))

    expect(durations.map((duration) => duration.value)).not.toContain('permanent')
    expect(Math.max(...durations.map((duration) => Number(duration.value)))).toBe(SEVEN_DAYS)
    wrapper.unmount()
  })

  it('sends the ASN ban with the chosen duration in seconds', async () => {
    const { api, mount } = setup(['MODERATOR', 'VIEWER'])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="area-value"] input').setValue('AS4134')
    await wrapper.get('[data-testid="area-reason"] input').setValue('Proxy farm')
    await chooseOption(wrapper.get('[data-testid="area-duration"] [role="combobox"]'), String(THREE_DAYS))
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.createAreaBan).toHaveBeenCalledWith({ kind: 'ASN', value: 'AS4134', reason: 'Proxy farm', durationSeconds: THREE_DAYS })
    wrapper.unmount()
  })

  it('keeps the confirm button disabled while the reason is blank', async () => {
    const { api, mount } = setup(['MODERATOR', 'VIEWER'])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="area-value"] input').setValue('4134')
    await wrapper.get('[data-testid="area-reason"] input').setValue('   ')
    await wrapper.get('[data-testid="confirm"]').trigger('click')

    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled')).toBeDefined()
    expect(api.createAreaBan, 'nothing is sent without a reason').not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('AreaBanCreateDialog for an admin', () => {
  it('offers the ASN and the country kind', async () => {
    const { mount } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])
    const { wrapper } = await mount()

    const kinds = await listOptions(wrapper.get('[data-testid="area-kind"] [role="combobox"]'))

    expect(kinds.map((kind) => kind.value)).toEqual(['ASN', 'COUNTRY'])
    wrapper.unmount()
  })

  it('offers a permanent duration', async () => {
    const { mount } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])
    const { wrapper } = await mount()

    const durations = await listOptions(wrapper.get('[data-testid="area-duration"] [role="combobox"]'))

    expect(durations.map((duration) => duration.value)).toContain('permanent')
    wrapper.unmount()
  })
})

describe('AreaBanCreateDialog errors', () => {
  it('shows the refusal of the server', async () => {
    const { mount } = setup(['MODERATOR', 'VIEWER'], {
      createAreaBan: vi.fn(async () => fail('This ASN is already banned.', 409, 'CONFLICT')),
    })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="area-value"] input').setValue('4134')
    await wrapper.get('[data-testid="area-reason"] input').setValue('Proxy farm')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('This ASN is already banned.')
    wrapper.unmount()
  })
})
