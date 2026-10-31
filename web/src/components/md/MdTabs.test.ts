import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MdTabs from './MdTabs.vue'
import { panelDomId, tabDomId, tabIndexAfterKey } from './tabs'

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'conversation', label: 'Conversation', badge: 2, badgeLabel: '2 new messages' },
  { id: 'audit', label: 'Notes & audit' },
]

function setup(modelValue = 'overview') {
  return mount(MdTabs, { props: { tabs: TABS, modelValue, label: 'Case sections', idPrefix: 'case' }, attachTo: document.body })
}

const tabsOf = (wrapper: ReturnType<typeof setup>) => wrapper.findAll('[role="tab"]')

describe('tabIndexAfterKey', () => {
  it('moves right and left and wraps around at the ends', () => {
    expect(tabIndexAfterKey('ArrowRight', 0, 3)).toBe(1)
    expect(tabIndexAfterKey('ArrowRight', 2, 3), 'last to first').toBe(0)
    expect(tabIndexAfterKey('ArrowLeft', 0, 3), 'first to last').toBe(2)
  })

  it('jumps to the ends with Home and End', () => {
    expect(tabIndexAfterKey('Home', 2, 3)).toBe(0)
    expect(tabIndexAfterKey('End', 0, 3)).toBe(2)
  })

  it('ignores other keys and an empty list', () => {
    expect(tabIndexAfterKey('Enter', 0, 3)).toBeNull()
    expect(tabIndexAfterKey('ArrowRight', 0, 0)).toBeNull()
  })
})

describe('MdTabs: wiring', () => {
  it('renders a labelled tablist with one tab per item, each pointing at its panel', () => {
    const wrapper = setup()

    expect(wrapper.get('[role="tablist"]').attributes('aria-label')).toBe('Case sections')
    expect(tabsOf(wrapper)[0]!.text()).toBe('Overview')
    expect(tabsOf(wrapper)[1]!.text(), 'the count and its label are part of the tab').toContain('Conversation')
    expect(tabsOf(wrapper)[2]!.text()).toBe('Notes & audit')
    expect(tabsOf(wrapper)[0]!.attributes('id')).toBe(tabDomId('case', 'overview'))
    expect(tabsOf(wrapper)[1]!.attributes('aria-controls')).toBe(panelDomId('case', 'conversation'))

    wrapper.unmount()
  })

  it('marks only the model value as selected and keeps it the one tab in the tab order', () => {
    const wrapper = setup('conversation')

    expect(tabsOf(wrapper).map((tab) => tab.attributes('aria-selected'))).toEqual(['false', 'true', 'false'])
    expect(tabsOf(wrapper).map((tab) => tab.attributes('tabindex'))).toEqual(['-1', '0', '-1'])

    wrapper.unmount()
  })

  it('shows the count of a tab as a badge, and none at zero', () => {
    const wrapper = setup()

    expect(wrapper.get('[data-testid="tab-badge-conversation"]').text()).toBe('2')
    expect(wrapper.find('[data-testid="tab-badge-overview"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('MdTabs: selecting', () => {
  it('selects a tab on click', async () => {
    const wrapper = setup()

    await tabsOf(wrapper)[2]!.trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([['audit']])

    wrapper.unmount()
  })

  it('moves the selection and the focus with the arrow keys, wrapping at the end', async () => {
    const wrapper = setup('audit')

    await tabsOf(wrapper)[2]!.trigger('keydown', { key: 'ArrowRight' })

    expect(wrapper.emitted('update:modelValue')).toEqual([['overview']])
    expect(document.activeElement, 'the new tab has the focus').toBe(tabsOf(wrapper)[0]!.element)

    wrapper.unmount()
  })

  it('jumps to the first and last tab with Home and End', async () => {
    const wrapper = setup('conversation')

    await tabsOf(wrapper)[1]!.trigger('keydown', { key: 'End' })
    await tabsOf(wrapper)[1]!.trigger('keydown', { key: 'Home' })

    expect(wrapper.emitted('update:modelValue')).toEqual([['audit'], ['overview']])

    wrapper.unmount()
  })

  it('leaves other keys to the browser', async () => {
    const wrapper = setup()

    await tabsOf(wrapper)[0]!.trigger('keydown', { key: 'a' })

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    wrapper.unmount()
  })
})
