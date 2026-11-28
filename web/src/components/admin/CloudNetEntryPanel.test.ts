import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CloudNetCatalogue } from '../../api/types'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import CloudNetEntryPanel from './CloudNetEntryPanel.vue'

const catalogue: CloudNetCatalogue = {
  available: true,
  reportedAt: '2026-10-23T12:30:00Z',
  tasks: [{ name: 'BedWars', groups: ['Games', 'Lobby'] }, { name: 'Proxy' }],
  groups: ['Games', 'Lobby'],
  services: [
    { name: 'BedWars-1', task: 'BedWars', running: true },
    { name: 'BedWars-2', task: 'BedWars', running: false },
  ],
  proxies: ['Proxy-1'],
}

function setup(path: string, result = ok(catalogue)) {
  const cloudnetCatalogue = vi.fn(async () => result)
  return { cloudnetCatalogue, mount: () => mountWithApp(CloudNetEntryPanel, { api: mockApi({ cloudnetCatalogue }), path, roles: ['ADMIN'] }) }
}

describe('CloudNetEntryPanel', () => {
  it('shows a task with its groups, read from the catalogue', async () => {
    const { mount } = setup('/admin/cloudnet/tasks/BedWars')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="cloudnet-entry"]').text()).toContain('BedWars')
    expect(wrapper.get('[data-testid="cloudnet-entry"]').text()).toContain('Games, Lobby')

    wrapper.unmount()
  })

  it('shows a service with its task and whether it runs', async () => {
    const { mount } = setup('/admin/cloudnet/services/BedWars-2')
    const { wrapper } = await mount()
    await flushPromises()

    const text = wrapper.get('[data-testid="cloudnet-entry"]').text()
    expect(text).toContain('BedWars-2')
    expect(text).toContain('Stopped')

    wrapper.unmount()
  })

  it('shows a group with the tasks that carry it', async () => {
    const { mount } = setup('/admin/cloudnet/groups/Games')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="cloudnet-entry"]').text()).toContain('BedWars')

    wrapper.unmount()
  })

  it('shows a proxy by its name', async () => {
    const { mount } = setup('/admin/cloudnet/proxies/Proxy-1')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="cloudnet-entry"]').text()).toContain('Proxy-1')
    expect(wrapper.find('[data-testid="cloudnet-entry-missing"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the not-found text for a name the catalogue does not have', async () => {
    const { mount } = setup('/admin/cloudnet/tasks/Unknown')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="cloudnet-entry-missing"]').text()).toContain('No CloudNet entry with this name')

    wrapper.unmount()
  })

  it('shows the not-found text for an unknown kind, even when the name exists under another kind', async () => {
    const { mount } = setup('/admin/cloudnet/players/Proxy-1')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="cloudnet-entry-missing"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('reads the catalogue and sends nothing else', async () => {
    const { cloudnetCatalogue, mount } = setup('/admin/cloudnet/tasks/BedWars')
    const { wrapper } = await mount()
    await flushPromises()

    expect(cloudnetCatalogue).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })
})
