import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
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
    const { wrapper, router } = await mountWithApp(PlayersView, { path: '/players', api: mockApi({ resolvePlayer }) })

    await search(wrapper, 'Nobody')

    expect(wrapper.get('[role="alert"]').text()).toContain('No player named "Nobody" was found.')
    expect(router.currentRoute.value.fullPath).toBe('/players')

    wrapper.unmount()
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
