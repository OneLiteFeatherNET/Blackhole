import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { mountWithApp } from '../../test/mount'
import EntityHeader from './EntityHeader.vue'
import type { EntityAction } from './entityHeader'

function props(overrides: Record<string, unknown> = {}) {
  return {
    typeLabel: 'Case',
    heading: 'Case #AB12CD',
    shortId: '#AB12CD',
    states: [{ label: 'Open', tone: 'primary', testid: 'case-status' }],
    owner: { label: 'abcdef12…' },
    assignee: { label: 'Mira', unassigned: false },
    primary: { key: 'close', label: 'Close', testid: 'close-case' } as EntityAction,
    overflow: [],
    crumbs: [{ label: 'Cases', to: '/cases' }, { label: '#AB12CD' }],
    ...overrides,
  }
}

describe('EntityHeader: the parts in order', () => {
  it('shows the type, the short id, the state in words, the owner and the assignee', async () => {
    const { wrapper } = await mountWithApp(EntityHeader, { props: props() })

    expect(wrapper.get('[data-testid="entity-type"]').text()).toBe('Case')
    expect(wrapper.get('[data-testid="entity-short-id"]').text()).toBe('#AB12CD')
    expect(wrapper.get('[data-testid="case-status"]').text()).toBe('Open')
    expect(wrapper.get('[data-testid="entity-owner"]').text()).toContain('abcdef12…')
    expect(wrapper.get('[data-testid="entity-assignee"]').text()).toContain('Mira')
    expect(wrapper.get('h1').text()).toBe('Case #AB12CD')

    wrapper.unmount()
  })

  it('shows Unassigned as text when nobody has the entity', async () => {
    const { wrapper } = await mountWithApp(EntityHeader, { props: props({ assignee: { label: 'Unassigned', unassigned: true } }) })

    expect(wrapper.get('[data-testid="entity-assignee"]').text()).toContain('Unassigned')

    wrapper.unmount()
  })

  it('leaves out the assignee part of an entity without one', async () => {
    const { wrapper } = await mountWithApp(EntityHeader, { props: props({ assignee: undefined }) })

    expect(wrapper.find('[data-testid="entity-assignee"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('EntityHeader: the breadcrumb', () => {
  it('links every part but the current one, and marks the current one', async () => {
    const { wrapper } = await mountWithApp(EntityHeader, { props: props() })

    const links = wrapper.findAll('nav[aria-label="Breadcrumbs"] a')
    expect(links.map((link) => link.text())).toEqual(['Cases'])
    expect(links[0]!.attributes('href')).toBe('/cases')
    expect(wrapper.get('nav[aria-label="Breadcrumbs"] [aria-current="page"]').text()).toBe('#AB12CD')

    wrapper.unmount()
  })
})

describe('EntityHeader: the primary action and its disabled reason', () => {
  it('shows a disabled action with its reason as text, linked with aria-describedby', async () => {
    const primary = { key: 'apply', label: 'Decide', testid: 'decide', disabled: true, reason: 'This appeal is already decided.' }
    const { wrapper } = await mountWithApp(EntityHeader, { props: props({ primary }) })

    const button = wrapper.get('[data-testid="decide"]')
    expect(button.attributes('disabled')).toBeDefined()
    const reasonId = button.attributes('aria-describedby')
    expect(reasonId).toBeTruthy()
    expect(wrapper.get(`[id="${reasonId}"]`).text()).toBe('This appeal is already decided.')

    wrapper.unmount()
  })

  it('shows the notice as text under the header', async () => {
    const { wrapper } = await mountWithApp(EntityHeader, { props: props({ primary: null, notice: 'A viewer cannot change a case.' }) })

    expect(wrapper.find('[data-testid="close-case"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="entity-notice"]').text()).toBe('A viewer cannot change a case.')

    wrapper.unmount()
  })

  it('emits the key of an enabled action when it is pressed', async () => {
    const { wrapper } = await mountWithApp(EntityHeader, { props: props() })

    await wrapper.get('[data-testid="close-case"]').trigger('click')

    expect(wrapper.emitted('action')).toEqual([['close']])

    wrapper.unmount()
  })

  it('navigates to the route of an action that names one, and emits nothing', async () => {
    const primary = { key: 'close', label: 'Close', testid: 'close-case', to: { name: 'cases' } }
    const { wrapper, router } = await mountWithApp(EntityHeader, { props: props({ primary }), path: '/cases/p-0001' })

    await wrapper.get('[data-testid="close-case"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('action')).toBeUndefined()
    expect(router.currentRoute.value.name).toBe('cases')

    wrapper.unmount()
  })

  it('makes the primary action a 48 pixel target', async () => {
    const { wrapper } = await mountWithApp(EntityHeader, { props: props() })

    expect(wrapper.get('[data-testid="close-case"]').classes()).toContain('min-h-12')

    wrapper.unmount()
  })
})

describe('EntityHeader: the overflow menu', () => {
  it('lists the secondary actions and emits the one that is chosen', async () => {
    const overflow = [
      { key: 'warn', label: 'Warn' },
      { key: 'kick', label: 'Kick', destructive: true },
    ]
    const { wrapper } = await mountWithApp(EntityHeader, { props: props({ overflow }) })

    await wrapper.get('button[aria-haspopup="menu"]').trigger('click')
    expect(wrapper.findAll('[role="menuitem"]').map((item) => item.text())).toEqual(['Warn', 'Kick'])
    await wrapper.findAll('[role="menuitem"]')[1]!.trigger('click')

    expect(wrapper.emitted('action')).toEqual([['kick']])

    wrapper.unmount()
  })

  it('has no menu when there is no secondary action', async () => {
    const { wrapper } = await mountWithApp(EntityHeader, { props: props({ overflow: [] }) })

    expect(wrapper.find('button[aria-haspopup="menu"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('EntityHeader: copy link', () => {
  it('offers the copy-link action of the page', async () => {
    const { wrapper } = await mountWithApp(EntityHeader, { props: props() })

    expect(wrapper.get('[data-testid="copy-link"]').text()).toBe('Copy link')

    wrapper.unmount()
  })
})
