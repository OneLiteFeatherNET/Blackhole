import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { NameHistoryOwner } from '../api/types'
import { useLookupStore } from '../stores/lookup'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import PlayersView from './PlayersView.vue'

const OWNER = 'ab'.repeat(64)
const STEVE = { uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5', name: 'Steve', source: 'otis', owner: OWNER }

async function search(wrapper: Awaited<ReturnType<typeof mountWithApp>>['wrapper'], name: string) {
  await wrapper.get('input').setValue(name)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('PlayersView', () => {
  it('has one level-one heading, the page title, with the lookup intro as its description', async () => {
    const { wrapper } = await mountWithApp(PlayersView, { path: '/players', api: mockApi() })

    expect(wrapper.findAll('h1').map((heading) => heading.text())).toEqual(['Player lookup'])
    expect(wrapper.get('[data-testid="page-description"]').text()).toContain('Look up a player by their current Minecraft name.')

    wrapper.unmount()
  })

  it('resolves the name and opens the player detail by owner hash', async () => {
    const resolvePlayer = vi.fn(async () => ok(STEVE))
    const { wrapper, router } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer }) })

    await search(wrapper, 'Steve')

    expect(resolvePlayer).toHaveBeenCalledWith('Steve')
    expect(router.currentRoute.value.fullPath).toBe(`/players/${OWNER}`)
    expect(useLookupStore().nameOf(OWNER), 'name kept in memory for the detail page').toBe('Steve')

    wrapper.unmount()
  })

  it('shows a not-found message and stays on the page for an unknown player', async () => {
    const resolvePlayer = vi.fn(async () => fail('No resolver found a player named Nobody', 404, 'NOT_FOUND'))
    const findNameOwners = vi.fn(async () => ok([]))
    const { wrapper, router } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer, findNameOwners }) })

    await search(wrapper, 'Nobody')

    expect(wrapper.get('[role="alert"]').text()).toContain('No player named "Nobody" was found.')
    expect(router.currentRoute.value.fullPath).toBe('/players')

    wrapper.unmount()
  })

  describe('old names', () => {
    const OTHER_OWNER = 'cd'.repeat(64)
    const notFound = () => fail('No resolver found a player named Alex', 404, 'NOT_FOUND')
    const owner = (overrides: Partial<NameHistoryOwner> = {}): NameHistoryOwner => ({
      owner: OWNER,
      playerUuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5',
      currentName: 'Steve',
      firstSeen: Date.UTC(2030, 0, 1),
      lastSeen: Date.UTC(2030, 5, 1),
      ...overrides,
    })

    it('tries the old-name lookup when the live lookup finds nothing and opens the single account', async () => {
      const resolvePlayer = vi.fn(async () => notFound())
      const findNameOwners = vi.fn(async () => ok([owner()]))
      const { wrapper, router } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer, findNameOwners }) })

      await search(wrapper, 'Alex')

      expect(findNameOwners).toHaveBeenCalledWith('Alex')
      expect(router.currentRoute.value.fullPath).toBe(`/players/${OWNER}`)
      expect(useLookupStore().nameOf(OWNER), 'the current name of the account').toBe('Steve')

      wrapper.unmount()
    })

    it('lists the accounts when the old name matches several and opens the one chosen', async () => {
      const resolvePlayer = vi.fn(async () => notFound())
      const findNameOwners = vi.fn(async () => ok([owner(), owner({ owner: OTHER_OWNER, currentName: 'Notch' })]))
      const { wrapper, router } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer, findNameOwners }) })

      await search(wrapper, 'Alex')

      expect(router.currentRoute.value.fullPath, 'no account is opened before the choice').toBe('/players')
      const choices = wrapper.findAll('[data-testid="owner-choice"]')
      expect(choices.map((choice) => choice.text())).toEqual([expect.stringContaining('Steve'), expect.stringContaining('Notch')])

      await choices[1]!.trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.fullPath).toBe(`/players/${OTHER_OWNER}`)

      wrapper.unmount()
    })

    it('shows the not-found message when the old-name lookup finds no account either', async () => {
      const resolvePlayer = vi.fn(async () => notFound())
      const findNameOwners = vi.fn(async () => ok([]))
      const { wrapper, router } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer, findNameOwners }) })

      await search(wrapper, 'Alex')

      expect(wrapper.get('[role="alert"]').text()).toContain('No player named "Alex" was found.')
      expect(router.currentRoute.value.fullPath).toBe('/players')

      wrapper.unmount()
    })

    it('does not try the old-name lookup when the live lookup fails for another reason', async () => {
      const resolvePlayer = vi.fn(async () => fail('backend down', 0, 'NETWORK'))
      const findNameOwners = vi.fn()
      const { wrapper } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer, findNameOwners }) })

      await search(wrapper, 'Steve')

      expect(findNameOwners).not.toHaveBeenCalled()
      expect(wrapper.get('[role="alert"]').text()).toContain('backend down')

      wrapper.unmount()
    })

    it('writes nothing to browser storage during the fallback', async () => {
      const setItem = vi.spyOn(Storage.prototype, 'setItem')
      const resolvePlayer = vi.fn(async () => notFound())
      const findNameOwners = vi.fn(async () => ok([owner(), owner({ owner: OTHER_OWNER })]))
      const { wrapper } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer, findNameOwners }) })

      await search(wrapper, 'Alex')

      expect(setItem).not.toHaveBeenCalled()

      setItem.mockRestore()
      wrapper.unmount()
    })
  })

  it('rejects a name that is not a Minecraft name without calling the API', async () => {
    const resolvePlayer = vi.fn()
    const { wrapper } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer }) })

    await search(wrapper, 'a b')

    expect(resolvePlayer).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('3 to 16 letters, digits or underscores')

    wrapper.unmount()
  })

  it('shows the backend error when the lookup fails for another reason', async () => {
    const resolvePlayer = vi.fn(async () => fail('backend down', 0, 'NETWORK'))
    const { wrapper } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer }) })

    await search(wrapper, 'Steve')

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')

    wrapper.unmount()
  })
})
