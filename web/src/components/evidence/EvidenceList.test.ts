import { describe, expect, it } from 'vitest'
import { evidenceItem } from '../../test/evidence'
import { mockApi, mountWithApp } from '../../test/mount'
import EvidenceList from './EvidenceList.vue'

const api = mockApi({ evidenceContentUrl: (id: string) => `/api/evidence/${id}/content` })

function mountList(items: ReturnType<typeof evidenceItem>[], extra: Record<string, unknown> = {}) {
  return mountWithApp(EvidenceList, { api, props: { items, page: 0, totalPages: 1, ...extra } })
}

describe('EvidenceList', () => {
  it('shows the empty state without items', async () => {
    const { wrapper } = await mountList([])

    expect(wrapper.get('[data-testid="empty"]').text()).toBe('No evidence for this punishment.')
    expect(wrapper.find('ul').exists()).toBe(false)

    wrapper.unmount()
  })

  it.each([
    ['ANTICHEAT', 'Anticheat record'],
    ['REPLAY', 'Replay'],
    ['CHAT_LOG', 'Chat excerpt'],
    ['LINK', 'Link'],
    ['FILE', 'File'],
  ] as const)('shows a %s item with its own icon and label', async (kind, label) => {
    const { wrapper } = await mountList([evidenceItem({ kind })])

    const icon = wrapper.get('[data-testid="kind-icon"]')
    expect(icon.attributes('data-kind')).toBe(kind)
    expect(icon.get('path').attributes('d')).not.toBe('')
    expect(wrapper.get('h4').text()).toBe(label)

    wrapper.unmount()
  })

  it('draws a different icon for each kind', async () => {
    const kinds = ['ANTICHEAT', 'REPLAY', 'CHAT_LOG', 'LINK', 'FILE'] as const
    const { wrapper } = await mountList(kinds.map((kind) => evidenceItem({ kind })))

    const paths = wrapper.findAll('[data-testid="kind-icon"] path').map((path) => path.attributes('d'))
    expect(new Set(paths).size).toBe(kinds.length)

    wrapper.unmount()
  })

  it('summarises an item with its source system and captured time', async () => {
    const item = evidenceItem({ kind: 'ANTICHEAT', sourceSystem: 'grim', capturedAt: Date.UTC(2026, 6, 2, 8, 30), payload: { check: 'Speed' } })
    const { wrapper } = await mountList([item])

    expect(wrapper.get('[data-testid="summary"]').text()).toBe('Speed')
    expect(wrapper.text()).toContain('grim')

    wrapper.unmount()
  })

  it('opens and closes the details of an item', async () => {
    const item = evidenceItem({ kind: 'ANTICHEAT', payload: { check: 'Speed', server: 'survival-1' } })
    const { wrapper } = await mountList([item])

    expect(wrapper.find('[data-testid="detail"]').exists(), 'closed at first').toBe(false)
    await wrapper.get('[data-testid="expand"]').trigger('click')
    expect(wrapper.get('[data-testid="detail"]').text()).toContain('survival-1')
    expect(wrapper.get('[data-testid="expand"]').attributes('aria-expanded')).toBe('true')
    await wrapper.get('[data-testid="expand"]').trigger('click')
    expect(wrapper.find('[data-testid="detail"]').exists(), 'closed again').toBe(false)

    wrapper.unmount()
  })

  it('shows a link as an anchor without a details toggle', async () => {
    const item = evidenceItem({ kind: 'LINK', reference: 'https://example.org/thread/1' })
    const { wrapper } = await mountList([item])

    expect(wrapper.get('a').attributes('href')).toBe('https://example.org/thread/1')
    expect(wrapper.find('[data-testid="expand"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows a stored javascript: reference as plain text', async () => {
    const { wrapper } = await mountList([evidenceItem({ kind: 'LINK', reference: 'javascript:alert(1)' })])

    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.get('[data-testid="plain-link"]').text()).toBe('javascript:alert(1)')

    wrapper.unmount()
  })

  it('asks to delete the item whose button was pressed', async () => {
    const items = [evidenceItem({ kind: 'LINK' }), evidenceItem({ kind: 'REPLAY' })]
    const { wrapper } = await mountList(items)

    await wrapper.findAll('[data-testid="delete"]')[1]!.trigger('click')

    expect(wrapper.emitted('delete')).toEqual([[items[1]]])

    wrapper.unmount()
  })

  it('shows no paging for a single page', async () => {
    const { wrapper } = await mountList([evidenceItem()])

    expect(wrapper.find('nav').exists()).toBe(false)

    wrapper.unmount()
  })

  it('pages forward and back and disables the ends', async () => {
    const { wrapper } = await mountList([evidenceItem()], { page: 1, totalPages: 3 })

    expect(wrapper.get('nav').text()).toContain('Page 2 of 3')
    await wrapper.get('[data-testid="next"]').trigger('click')
    await wrapper.get('[data-testid="previous"]').trigger('click')
    expect(wrapper.emitted('page')).toEqual([[2], [0]])

    await wrapper.setProps({ page: 0 })
    expect(wrapper.get('[data-testid="previous"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })
})
