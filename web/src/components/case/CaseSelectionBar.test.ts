import { flushPromises } from '@vue/test-utils'
import { h, type Slots } from 'vue'
import { describe, expect, it } from 'vitest'
import { mountWithApp } from '../../test/mount'
import CaseSelectionBar from './CaseSelectionBar.vue'

function setup(props: { count: number; atCap?: boolean; cap?: number }, slots: Slots = {}) {
  return mountWithApp(CaseSelectionBar, { props: { atCap: false, cap: 100, ...props }, slots })
}

describe('CaseSelectionBar', () => {
  it('is not shown while nothing is selected', async () => {
    const { wrapper } = await setup({ count: 0 })

    expect(wrapper.find('[data-testid="selection-bar"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the number of selected cases', async () => {
    const { wrapper } = await setup({ count: 3 })

    expect(wrapper.get('[data-testid="selection-count"]').text()).toBe('3 selected')

    wrapper.unmount()
  })

  it('emits clear when the clear control is used', async () => {
    const { wrapper } = await setup({ count: 2 })

    await wrapper.get('[data-testid="selection-clear"]').trigger('click')

    expect(wrapper.emitted('clear')).toHaveLength(1)

    wrapper.unmount()
  })

  it('says that the selection is capped only when the cap is reached', async () => {
    const { wrapper } = await setup({ count: 100, atCap: true, cap: 100 })

    expect(wrapper.get('[data-testid="selection-limit"]').text()).toBe('Selection is limited to 100 cases.')

    await wrapper.setProps({ count: 99, atCap: false })

    expect(wrapper.find('[data-testid="selection-limit"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('announces the count in a polite live region, also when it drops to zero', async () => {
    const { wrapper } = await setup({ count: 4 })

    const region = wrapper.get('[role="status"]')
    expect(region.attributes('aria-live')).toBe('polite')
    expect(region.text()).toBe('4 selected')

    await wrapper.setProps({ count: 0 })
    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toBe('0 selected')

    wrapper.unmount()
  })

  it('leaves room for bulk actions in its actions slot', async () => {
    const { wrapper } = await setup({ count: 1 }, { actions: () => [h('button', { 'data-testid': 'bulk-action' }, 'Apply')] })

    expect(wrapper.get('[data-testid="bulk-action"]').text()).toBe('Apply')

    wrapper.unmount()
  })
})
