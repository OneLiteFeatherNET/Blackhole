import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { NameHistoryName, NameHistoryPage } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import NameHistory from './NameHistory.vue'

const OWNER = 'ab'.repeat(64)
const UUID = '11111111-2222-3333-4444-555555555555'

function name(overrides: Partial<NameHistoryName> = {}): NameHistoryName {
  return { name: 'Steve', firstSeen: Date.UTC(2030, 0, 1, 12), lastSeen: Date.UTC(2030, 0, 2, 12), ...overrides }
}

function page(names: NameHistoryName[], hasMore = false, pageNumber = 0): NameHistoryPage {
  return { owner: OWNER, playerUuid: UUID, names, page: pageNumber, size: 50, hasMore }
}

function api(overrides: Record<string, unknown> = {}) {
  return mockApi({ listPlayerNames: vi.fn(async () => ok(page([name()]))), ...overrides })
}

async function open(overrides: Record<string, unknown> = {}) {
  return mountWithApp(NameHistory, { props: { owner: OWNER }, api: api(overrides) })
}

describe('NameHistory', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('lists the names in the order the backend answers, newest first, with first and last seen', async () => {
    const names = [
      name({ name: 'Alex', firstSeen: Date.UTC(2030, 2, 5, 9), lastSeen: Date.UTC(2030, 2, 6, 9) }),
      name({ name: 'Steve', firstSeen: Date.UTC(2030, 0, 1, 12), lastSeen: Date.UTC(2030, 0, 2, 12) }),
    ]
    const { wrapper } = await open({ listPlayerNames: vi.fn(async () => ok(page(names))) })

    const rows = wrapper.get('table[aria-label="Name history"]').findAll('tbody tr')
    expect(rows.map((row) => row.findAll('td')[0]!.text()), 'names, newest first').toEqual(['Alex', 'Steve'])
    expect(rows[1]!.text(), 'first and last seen of Steve').toContain('Jan 1, 2030')
    expect(rows[1]!.text()).toContain('Jan 2, 2030')

    wrapper.unmount()
  })

  it('asks for the first page of fifty names of the owner', async () => {
    const listPlayerNames = vi.fn(async () => ok(page([name()])))
    const { wrapper } = await open({ listPlayerNames })

    expect(listPlayerNames).toHaveBeenCalledWith(OWNER, 0, 50)

    wrapper.unmount()
  })

  it('notes that names from before the first recorded login are not known', async () => {
    const { wrapper } = await open()

    expect(wrapper.get('[data-testid="names-history-note"]').text()).toContain('earlier names are not known')

    wrapper.unmount()
  })

  it('shows the empty state when no name has been recorded', async () => {
    const { wrapper } = await open({ listPlayerNames: vi.fn(async () => ok(page([]))) })

    expect(wrapper.text()).toContain('No name has been recorded for this player yet.')
    expect(wrapper.find('table[aria-label="Name history"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the backend error with a retry and loads the names after a retry', async () => {
    const listPlayerNames = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok(page([name()])))
    const { wrapper } = await open({ listPlayerNames })

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('table[aria-label="Name history"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('appends the next page when the owner has more names', async () => {
    const listPlayerNames = vi.fn(async (_owner: string, pageNumber: number) =>
      ok(pageNumber === 0 ? page([name({ name: 'Alex' })], true, 0) : page([name({ name: 'Steve' })], false, 1)),
    )
    const { wrapper } = await open({ listPlayerNames })

    await wrapper.get('[data-testid="load-more"]').trigger('click')
    await flushPromises()

    expect(listPlayerNames).toHaveBeenLastCalledWith(OWNER, 1, 50)
    expect(wrapper.findAll('table[aria-label="Name history"] tbody tr')).toHaveLength(2)

    wrapper.unmount()
  })

  it('keeps the names out of browser storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const { wrapper } = await open()

    expect(setItem).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})
