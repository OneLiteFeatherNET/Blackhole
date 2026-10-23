import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CloudNetCatalogue } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import CloudNetCataloguePanel from './CloudNetCataloguePanel.vue'

const catalogue: CloudNetCatalogue = {
  available: true,
  reportedAt: '2026-10-23T12:30:00Z',
  tasks: [{ name: 'BedWars', groups: ['Games', 'Lobby'] }, { name: 'Proxy' }],
  groups: ['Games', 'Lobby'],
  services: [
    { name: 'BedWars-1', task: 'BedWars', running: true },
    { name: 'BedWars-2', task: 'BedWars', running: false },
  ],
  proxies: ['Proxy-1', 'Proxy-2'],
}

function setup(result: unknown = ok(catalogue)) {
  const cloudnetCatalogue = vi.fn(async () => result)
  return { cloudnetCatalogue, mount: () => mountWithApp(CloudNetCataloguePanel, { api: mockApi({ cloudnetCatalogue }) }) }
}

describe('CloudNetCataloguePanel', () => {
  it('lists tasks with their groups and a placeholder for a task without groups', async () => {
    const { wrapper } = await setup().mount()

    const rows = wrapper.findAll('[data-testid="tasks"] tbody tr')
    expect(rows, 'task rows').toHaveLength(2)
    expect(rows[0]!.text()).toContain('Games, Lobby')
    expect(rows[1]!.text(), 'no groups').toContain('none')

    wrapper.unmount()
  })

  it('lists the groups', async () => {
    const { wrapper } = await setup().mount()

    expect(wrapper.findAll('[data-testid="groups"] li').map((li) => li.text())).toEqual(['Games', 'Lobby'])

    wrapper.unmount()
  })

  it('states for every service in words whether it runs or is stopped', async () => {
    const { wrapper } = await setup().mount()

    const rows = wrapper.findAll('[data-testid="services"] tbody tr')
    expect(rows[0]!.text(), 'running').toContain('Running')
    expect(rows[1]!.text(), 'stopped').toContain('Stopped')

    wrapper.unmount()
  })

  it('names the reporting proxies', async () => {
    const { wrapper } = await setup().mount()

    expect(wrapper.findAll('[data-testid="proxies"] li').map((li) => li.text())).toEqual(['Proxy-1', 'Proxy-2'])

    wrapper.unmount()
  })

  it('shows when the data was last reported', async () => {
    const { wrapper } = await setup().mount()

    expect(wrapper.get('[data-testid="freshness"]').text()).toMatch(/^Last reported: .*2026/)

    wrapper.unmount()
  })

  it('says the time is unknown when the report carries none', async () => {
    const { wrapper } = await setup(ok({ ...catalogue, reportedAt: undefined })).mount()

    expect(wrapper.get('[data-testid="freshness"]').text()).toBe('Time of the last report unknown.')

    wrapper.unmount()
  })

  it('shows an empty state and no tables when no catalogue exists', async () => {
    const { wrapper } = await setup(ok({ available: false, tasks: [], groups: [], services: [], proxies: [] })).mount()

    expect(wrapper.get('[data-testid="cloudnet-empty"]').text()).toContain('No CloudNet data has been reported yet')
    expect(wrapper.find('table').exists(), 'no tables').toBe(false)

    wrapper.unmount()
  })

  it('says so when a kind has no entries', async () => {
    const { wrapper } = await setup(ok({ ...catalogue, tasks: [], groups: [], services: [], proxies: [] })).mount()

    expect(wrapper.text()).toContain('No tasks reported.')
    expect(wrapper.text()).toContain('No groups reported.')
    expect(wrapper.text()).toContain('No services reported.')
    expect(wrapper.text()).toContain('No proxy is reporting.')

    wrapper.unmount()
  })

  it('shows the error with a retry that loads again', async () => {
    const cloudnetCatalogue = vi.fn().mockResolvedValueOnce(fail('boom', 500, 'UNKNOWN')).mockResolvedValueOnce(ok(catalogue))
    const { wrapper } = await mountWithApp(CloudNetCataloguePanel, { api: mockApi({ cloudnetCatalogue }) })
    expect(wrapper.get('[role="alert"]').text(), 'error').toContain('boom')

    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="tasks"]').exists(), 'content after retry').toBe(true)

    wrapper.unmount()
  })

  it('reloads on request', async () => {
    const { cloudnetCatalogue, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="reload"]').trigger('click')
    await flushPromises()

    expect(cloudnetCatalogue).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })
})
