import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Punishment } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import GlobalSearch from './GlobalSearch.vue'

const OWNER = 'ab'.repeat(64)
const PLAYER = { uuid: '11111111-2222-3333-4444-555555555555', name: 'Steve', source: 'otis', owner: OWNER }

function punishment(identifier: string, extra: Partial<Punishment> = {}): Punishment {
  return { identifier, shortId: '#AB12CD34', source: PLAYER.uuid, verified: false, section: 'chat', permanent: false, lifted: false, createdAt: 1_700_000_000_000, metaData: {}, ...extra } as Punishment
}

async function setup(methods: Parameters<typeof mockApi>[0] = {}) {
  const { wrapper, router } = await mountWithApp(GlobalSearch, { api: mockApi(methods), path: '/' })
  return { wrapper, router, input: wrapper.get<HTMLInputElement>('[data-testid="global-search"]') }
}

async function submit(input: ReturnType<VueWrapper['get']>, text: string) {
  await input.setValue(text)
  await input.trigger('keydown', { key: 'Enter' })
  await flushPromises()
}

const press = (key: string, init: KeyboardEventInit = {}, target: EventTarget = document.body) =>
  target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }))

describe('GlobalSearch', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('offers the interpreted action for a player name while typing', async () => {
    const { wrapper, input } = await setup()

    await input.setValue('Steve')

    expect(wrapper.findAll('[role="option"]').map((o) => o.text())).toEqual(['Open player "Steve"'])
    expect(input.attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })

  it('resolves a player name and opens the player page', async () => {
    const resolvePlayer = vi.fn(async () => ok(PLAYER))
    const { wrapper, router, input } = await setup({ resolvePlayer })

    await submit(input, ' Steve ')

    expect(resolvePlayer).toHaveBeenCalledWith('Steve')
    expect(router.currentRoute.value.fullPath).toBe(`/players/${OWNER}`)
    expect(input.element.value, 'field cleared').toBe('')
    wrapper.unmount()
  })

  it('says so when no player has that name and stays where it is', async () => {
    const { wrapper, router, input } = await setup({ resolvePlayer: async () => fail('no', 404, 'NOT_FOUND') })

    await submit(input, 'Nobody')

    expect(wrapper.get('[data-testid="search-message"]').text()).toBe('No player named "Nobody" was found.')
    expect(wrapper.get('[data-testid="search-message"]').attributes('role')).toBe('alert')
    expect(router.currentRoute.value.fullPath).toBe('/')
    wrapper.unmount()
  })

  it('shows the error of the backend when the lookup fails otherwise', async () => {
    const { wrapper, input } = await setup({ resolvePlayer: async () => fail('Resolver unreachable', 502, 'UNKNOWN') })

    await submit(input, 'Steve')

    expect(wrapper.get('[data-testid="search-message"]').text()).toBe('Resolver unreachable')
    wrapper.unmount()
  })

  it('explains what can be entered instead of calling the backend for text it does not understand', async () => {
    const { wrapper, input } = await setup()

    await submit(input, 'two words')

    expect(wrapper.get('[data-testid="search-message"]').text()).toBe('Enter a player name (3 to 16 characters) or a case id such as #A1B2C3D4.')
    wrapper.unmount()
  })

  it('opens the case of a short id that matches one punishment', async () => {
    const punishmentsByShortId = vi.fn(async () => ok([punishment('p-1')]))
    const { wrapper, router, input } = await setup({ punishmentsByShortId })

    await submit(input, '#ab12cd34')

    expect(punishmentsByShortId).toHaveBeenCalledWith('#AB12CD34')
    expect(router.currentRoute.value.fullPath).toBe('/cases/p-1')
    wrapper.unmount()
  })

  it('opens a full punishment id without asking the backend first', async () => {
    const { wrapper, router, input } = await setup()

    await submit(input, '3F2B8C1E-0000-4000-8000-000000000001')

    expect(router.currentRoute.value.fullPath).toBe('/cases/3f2b8c1e-0000-4000-8000-000000000001')
    wrapper.unmount()
  })

  it('says so when a short id matches nothing', async () => {
    const { wrapper, router, input } = await setup({ punishmentsByShortId: async () => ok([]) })

    await submit(input, '#AB12CD34')

    expect(wrapper.get('[data-testid="search-message"]').text()).toBe('Nothing matches the case id #AB12CD34.')
    expect(router.currentRoute.value.fullPath).toBe('/')
    wrapper.unmount()
  })

  it('lists the choices of a colliding short id and opens the one picked with the keyboard', async () => {
    const { wrapper, router, input } = await setup({
      punishmentsByShortId: async () => ok([punishment('p-1'), punishment('p-2', { section: 'game' })]),
    })

    await submit(input, '#AB12CD34')

    expect(wrapper.get('[data-testid="search-message"]').text()).toBe('The id #AB12CD34 matches 2 cases. Pick one.')
    const options = wrapper.findAll('[role="option"]')
    expect(options.map((o) => o.text())).toEqual([expect.stringContaining('chat'), expect.stringContaining('game')])
    expect(router.currentRoute.value.fullPath, 'nothing opened yet').toBe('/')

    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(input.attributes('aria-activedescendant')).toBe(options[1]!.attributes('id'))
    expect(options[1]!.attributes('aria-selected')).toBe('true')
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/cases/p-2')
    wrapper.unmount()
  })

  it('opens a choice on click', async () => {
    const { wrapper, router, input } = await setup({ punishmentsByShortId: async () => ok([punishment('p-1'), punishment('p-2')]) })
    await submit(input, '#AB12CD34')

    await wrapper.findAll('[role="option"]')[0]!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/cases/p-1')
    wrapper.unmount()
  })

  it('wraps the active option around with the arrow keys', async () => {
    const { wrapper, input } = await setup({ punishmentsByShortId: async () => ok([punishment('p-1'), punishment('p-2')]) })
    await submit(input, '#AB12CD34')
    const ids = wrapper.findAll('[role="option"]').map((o) => o.attributes('id'))

    await input.trigger('keydown', { key: 'ArrowUp' })
    expect(input.attributes('aria-activedescendant'), 'up from the first').toBe(ids[1])
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(input.attributes('aria-activedescendant'), 'down from the last').toBe(ids[0])
    wrapper.unmount()
  })

  it('ignores the answer to text that has been replaced meanwhile', async () => {
    let answer!: (value: ReturnType<typeof ok<typeof PLAYER>>) => void
    const resolvePlayer = vi.fn(() => new Promise((resolve) => (answer = resolve)))
    const { wrapper, router, input } = await setup({ resolvePlayer })
    await input.setValue('Steve')
    await input.trigger('keydown', { key: 'Enter' })

    await input.setValue('Alex')
    answer(ok(PLAYER))
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
    wrapper.unmount()
  })

  it('closes the list with the first Escape and empties the field with the second', async () => {
    const { wrapper, input } = await setup()
    await input.setValue('Steve')

    await input.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="option"]').exists(), 'list').toBe(false)
    expect(input.element.value, 'text kept').toBe('Steve')

    await input.trigger('keydown', { key: 'Escape' })
    expect(input.element.value, 'text cleared').toBe('')
    wrapper.unmount()
  })

  it('focuses the field when / is pressed outside a text field', async () => {
    const { wrapper, input } = await setup()

    press('/')

    expect(document.activeElement).toBe(input.element)
    wrapper.unmount()
  })

  it('does not steal / from another text field or when a modifier is held', async () => {
    const { wrapper, input } = await setup()
    const other = document.createElement('input')
    document.body.append(other)
    other.focus()

    press('/', {}, other)
    expect(document.activeElement, 'typing elsewhere').toBe(other)

    other.blur()
    press('/', { ctrlKey: true })
    expect(document.activeElement, 'with ctrl').not.toBe(input.element)
    wrapper.unmount()
  })

  it('stops listening for / once unmounted', async () => {
    const { wrapper, input } = await setup()
    const element = input.element
    wrapper.unmount()

    document.body.append(element)
    press('/')

    expect(document.activeElement).not.toBe(element)
  })

  it('exposes the field as a labelled combobox with the shortcut', async () => {
    const { wrapper, input } = await setup()

    expect(input.attributes('role')).toBe('combobox')
    expect(input.attributes('aria-label')).toBe('Search')
    expect(input.attributes('aria-keyshortcuts')).toBe('/')
    wrapper.unmount()
  })
})
