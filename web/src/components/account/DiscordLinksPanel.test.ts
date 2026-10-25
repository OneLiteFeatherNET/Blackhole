import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { DiscordLink } from '../../api/types'
import { chooseRowMenuItem, fail, mockApi, mountWithApp, ok } from '../../test/mount'
import DiscordLinksPanel from './DiscordLinksPanel.vue'

const staff: DiscordLink = { id: '00000000-0000-4000-8000-0000000000d1', kind: 'STAFF', subject: '00000000-0000-4000-8000-0000000000b2', displayName: 'Ada', linkedAt: Date.UTC(2026, 9, 23) }
const player: DiscordLink = { id: '00000000-0000-4000-8000-0000000000d2', kind: 'PLAYER', subject: 'abcdef0123456789abcdef0123456789', displayName: null, linkedAt: Date.UTC(2026, 9, 22) }
const nameless: DiscordLink = { ...staff, id: '00000000-0000-4000-8000-0000000000d3', displayName: null }

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    discordLinks: vi.fn(async () => ok([staff, player])),
    removeDiscordLink: vi.fn(async () => ok(undefined)),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(DiscordLinksPanel, { api: mockApi(api) }) }
}

describe('DiscordLinksPanel', () => {
  it('lists staff by name and players by the first 8 characters of the hashed owner', async () => {
    const { wrapper } = await setup().mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('Ada')
    expect(rows[0]!.text()).toContain('Staff')
    expect(rows[1]!.text()).toContain('Player')
    expect(rows[1]!.text()).toContain('abcdef01')
    expect(rows[1]!.text(), 'the rest of the hash is never rendered').not.toContain('abcdef012')

    wrapper.unmount()
  })

  it('falls back to an unnamed label for a staff link without a name', async () => {
    const { wrapper } = await setup({ discordLinks: vi.fn(async () => ok([nameless])) }).mount()

    expect(wrapper.get('tbody tr').text()).toContain('(no name)')

    wrapper.unmount()
  })

  it('offers the removal in the row menu, not as a button in the row', async () => {
    const { wrapper } = await setup().mount()

    const buttons = wrapper.findAll('tbody tr')[0]!.findAll('button').map((button) => button.attributes('aria-haspopup'))
    expect(buttons, 'only the menu trigger').toEqual(['menu'])

    wrapper.unmount()
  })

  it('says so when nothing is linked', async () => {
    const { wrapper } = await setup({ discordLinks: vi.fn(async () => ok([])) }).mount()

    expect(wrapper.get('[data-testid="discord-links-empty"]').text()).toBe('No Discord accounts are linked.')

    wrapper.unmount()
  })

  it('shows an error state when the list cannot be loaded', async () => {
    const { wrapper } = await setup({ discordLinks: vi.fn(async () => fail('backend down', 500)) }).mount()

    expect(wrapper.text()).toContain('backend down')
    expect(wrapper.find('tbody').exists(), 'no table').toBe(false)

    wrapper.unmount()
  })

  it('removes nothing on cancel', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await chooseRowMenuItem(wrapper, 0, 'Remove Discord link')
    await wrapper.get('[data-testid="discord-cancel"]').trigger('click')

    expect(api.removeDiscordLink).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('removes the chosen link after confirmation and reloads the list', async () => {
    const list = vi.fn().mockResolvedValueOnce(ok([staff, player])).mockResolvedValueOnce(ok([player]))
    const { api, mount } = setup({ discordLinks: list })
    const { wrapper } = await mount()

    await chooseRowMenuItem(wrapper, 0, 'Remove Discord link')
    expect(wrapper.text()).toContain('Remove the Discord link of Ada?')
    await wrapper.get('[data-testid="discord-confirm"]').trigger('click')
    await flushPromises()

    expect(api.removeDiscordLink).toHaveBeenCalledWith(staff.id)
    expect(wrapper.findAll('tbody tr'), 'the removed row is gone').toHaveLength(1)

    wrapper.unmount()
  })

  it('keeps the dialog open with the backend message when removing fails', async () => {
    const { wrapper } = await setup({ removeDiscordLink: vi.fn(async () => fail('link not found', 404, 'NOT_FOUND')) }).mount()

    await chooseRowMenuItem(wrapper, 0, 'Remove Discord link')
    await wrapper.get('[data-testid="discord-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="discord-error"]').text()).toBe('link not found')

    wrapper.unmount()
  })
})

