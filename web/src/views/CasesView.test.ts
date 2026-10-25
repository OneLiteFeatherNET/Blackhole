import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CaseListItem } from '../api/types'
import { ME, OTHER } from '../test/cases'
import { mockApi, mountWithApp, ok } from '../test/mount'
import CasesView from './CasesView.vue'

function item(overrides: Partial<CaseListItem> = {}): CaseListItem {
  return {
    punishmentIdentifier: 'p-0001',
    shortId: '#AB12CD',
    section: 'network',
    punishmentState: 'ACTIVE',
    status: 'OPEN',
    priority: 'HIGH',
    tags: ['spam', 'chat'],
    punishmentCreatedAt: Date.UTC(2026, 6, 1),
    lastActivityAt: Date.UTC(2026, 6, 2),
    ...overrides,
  }
}

function setup(pages: CaseListItem[][] = [[item(), item({ punishmentIdentifier: 'p-0002', shortId: '#ZZ99XX', status: 'CLOSED', assigneeOid: OTHER, assigneeName: 'Ada' })]], totalPages = 1) {
  const cases = vi.fn(async () => ok({ items: pages.shift() ?? [], page: 0, size: 25, totalSize: 2, totalPages }))
  return { cases, mount: () => mountWithApp(CasesView, { api: mockApi({ cases }), path: '/cases' }) }
}

describe('CasesView', () => {
  it('lists the cases with links to their pages', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.get('a').text()).toBe('#AB12CD')
    expect(rows[0]!.get('a').attributes('href')).toBe('/cases/p-0001')
    expect(rows[0]!.text()).toContain('spam, chat')
    expect(rows[0]!.text()).toContain('High')
    expect(rows[1]!.text()).toContain('Ada')
    expect(rows[1]!.text()).toContain('Closed')

    wrapper.unmount()
  })

  it('filters by status and starts again from the first page', async () => {
    const { cases, mount } = setup([[item()], [item()]])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="status-CLOSED"]').trigger('click')
    await flushPromises()

    expect(cases).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'CLOSED', page: 0 }))
    expect(wrapper.get('[data-testid="status-CLOSED"]').attributes('aria-pressed')).toBe('true')

    wrapper.unmount()
  })

  it('filters by the signed-in assignee', async () => {
    const { cases, mount } = setup([[item()], [item()]])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="filter-mine"]').trigger('click')
    await flushPromises()

    expect(cases).toHaveBeenLastCalledWith(expect.objectContaining({ assignee: ME }))

    wrapper.unmount()
  })

  it('filters by tag on submit and refuses a tag that cannot exist', async () => {
    const { cases, mount } = setup([[item()], [item()]])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="tag-filter"] input').setValue('Not A Tag')
    await wrapper.get('[data-testid="tag-filter"]').trigger('submit')
    await flushPromises()
    expect(cases).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-testid="tag-filter"] input').setValue('Spam')
    await wrapper.get('[data-testid="tag-filter"]').trigger('submit')
    await flushPromises()
    expect(cases).toHaveBeenLastCalledWith(expect.objectContaining({ tag: 'spam' }))
    expect(wrapper.get('[data-testid="applied-filters"]').text()).toContain('Tag: spam')

    wrapper.unmount()
  })

  it('pages through the cases', async () => {
    const { cases, mount } = setup([[item()], [item({ shortId: '#NEXT01' })]], 2)
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="prev"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="next"]').trigger('click')
    await flushPromises()

    expect(cases).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    expect(wrapper.get('tbody tr a').text()).toBe('#NEXT01')
    expect(wrapper.get('[data-testid="next"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('says that there are no cases yet when no filter is applied', async () => {
    const { mount } = setup([[]])
    const { wrapper } = await mount()

    const empty = wrapper.get('[data-testid="cases-empty"]')
    expect(empty.attributes('data-variant')).toBe('no-items')
    expect(empty.text()).toContain('No cases yet.')

    wrapper.unmount()
  })

  it('says that no case matches when the filters hide everything, and clears them from there', async () => {
    const { cases, mount } = setup([[item()], [], [item()]])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="status-CLOSED"]').trigger('click')
    await flushPromises()
    const empty = wrapper.get('[data-testid="cases-empty"]')
    expect(empty.attributes('data-variant')).toBe('no-matches')
    expect(empty.text()).toContain('No cases match.')

    await wrapper.get('[data-testid="empty-clear-filters"]').trigger('click')
    await flushPromises()

    expect(cases).toHaveBeenLastCalledWith(expect.objectContaining({ status: undefined, page: 0 }))
    expect(wrapper.get('[data-testid="status-all"]').attributes('aria-pressed')).toBe('true')

    wrapper.unmount()
  })

  it('shows the page title as its only h1', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.findAll('h1').map((heading) => heading.text())).toEqual(['Cases'])

    wrapper.unmount()
  })
})
