import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MdSegmentedButton, { type SegmentOption } from './MdSegmentedButton.vue'

const OPTIONS: SegmentOption<'cases' | 'player'>[] = [
  { value: 'cases', label: 'All cases', testid: 'view-cases' },
  { value: 'player', label: 'By player', testid: 'view-player' },
]

// Mounted into the document so that focus can move between the segments.
function mountSegmented(modelValue: 'cases' | 'player' = 'cases') {
  return mount(MdSegmentedButton<'cases' | 'player'>, {
    props: { modelValue, options: OPTIONS, label: 'View' },
    attachTo: document.body,
  })
}

describe('MdSegmentedButton', () => {
  it('marks only the chosen segment as pressed and shows a check on it', () => {
    const wrapper = mountSegmented('player')

    expect(wrapper.get('[data-testid="view-cases"]').attributes('aria-pressed')).toBe('false')
    expect(wrapper.get('[data-testid="view-player"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="view-player"]').find('svg').exists()).toBe(true)
    expect(wrapper.get('[data-testid="view-cases"]').find('svg').exists()).toBe(false)
    expect(wrapper.get('[data-testid="view-player"]').classes()).toContain('bg-secondary-container')

    wrapper.unmount()
  })

  it('names the group after its label', () => {
    const wrapper = mountSegmented()

    expect(wrapper.attributes('role')).toBe('group')
    expect(wrapper.attributes('aria-label')).toBe('View')

    wrapper.unmount()
  })

  it('emits the value of the segment that is clicked', async () => {
    const wrapper = mountSegmented('cases')

    await wrapper.get('[data-testid="view-player"]').trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([['player']])

    wrapper.unmount()
  })

  it('renders every segment as a native button, so the keyboard reaches each one with Tab', () => {
    const wrapper = mountSegmented()
    const segments = wrapper.findAll('button')

    expect(segments).toHaveLength(2)
    for (const segment of segments) {
      expect(segment.element.tagName).toBe('BUTTON')
      expect(segment.attributes('type')).toBe('button')
      expect(segment.attributes('tabindex')).toBeUndefined()
    }

    wrapper.unmount()
  })

  it('moves the focus to the next segment on ArrowRight without changing the choice', async () => {
    const wrapper = mountSegmented('cases')
    const [first, second] = wrapper.findAll('button')
    first!.element.focus()

    await first!.trigger('keydown', { key: 'ArrowRight' })

    expect(document.activeElement).toBe(second!.element)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    wrapper.unmount()
  })

  it('wraps the focus from the last segment to the first on ArrowRight', async () => {
    const wrapper = mountSegmented('cases')
    const [first, second] = wrapper.findAll('button')
    second!.element.focus()

    await second!.trigger('keydown', { key: 'ArrowRight' })

    expect(document.activeElement).toBe(first!.element)

    wrapper.unmount()
  })

  it('moves the focus with Home and End', async () => {
    const wrapper = mountSegmented('cases')
    const [first, second] = wrapper.findAll('button')
    first!.element.focus()

    await first!.trigger('keydown', { key: 'End' })
    expect(document.activeElement).toBe(second!.element)

    await second!.trigger('keydown', { key: 'Home' })
    expect(document.activeElement).toBe(first!.element)

    wrapper.unmount()
  })
})
