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

  it('filters by tag and refuses a tag that cannot exist', async () => {
    const { cases, mount } = setup([[item()], [item()]])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="tag-filter"] input').setValue('Not A Tag')
    await wrapper.get('[data-testid="apply-tag"]').trigger('click')
    await flushPromises()
    expect(cases).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-testid="tag-filter"] input').setValue('Spam')
    await wrapper.get('[data-testid="apply-tag"]').trigger('click')
    await flushPromises()
    expect(cases).toHaveBeenLastCalledWith(expect.objectContaining({ tag: 'spam' }))

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

  it('says when no case matches', async () => {
    const { mount } = setup([[]])
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="cases-empty"]').text()).toBe('No cases match.')

    wrapper.unmount()
  })
})
