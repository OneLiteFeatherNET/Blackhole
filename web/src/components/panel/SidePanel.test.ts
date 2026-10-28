import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { i18n } from '../../i18n'
import { stubReducedMotion } from '../../test/reducedMotion'
import SidePanel from './SidePanel.vue'

const mounted: VueWrapper[] = []
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

function mountPanel(props: Record<string, unknown> = {}, slot?: () => unknown) {
  i18n.global.locale.value = 'en'
  const wrapper = mount(SidePanel, {
    props: { title: 'New rule', sizeClass: 'expanded', ...props },
    slots: { default: slot ?? (() => h('button', { type: 'button' }, 'Save')) },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
  mounted.push(wrapper)
  return wrapper
}

describe('SidePanel header', () => {
  it('shows its title as the heading', () => {
    const wrapper = mountPanel({ title: 'New rule' })

    expect(wrapper.get('h2').text()).toBe('New rule')
  })

  it('shows the breadcrumb with the current item last and marked as the current page', () => {
    const wrapper = mountPanel({ title: 'New rule', breadcrumb: ['Client rules', 'New rule'] })

    const items = wrapper.findAll('nav ol li')
    expect(items.map((item) => item.text().replace('›', '').trim())).toEqual(['Client rules', 'New rule'])
    expect(wrapper.get('nav [aria-current="page"]').text(), 'the current item is the last one').toBe('New rule')
  })

  it('shows a back control only when a parent sheet exists', () => {
    expect(mountPanel({ parentTitle: undefined }).findAll('button[aria-label^="Back to"]')).toHaveLength(0)
  })

  it('names the back control after the parent it returns to', () => {
    const wrapper = mountPanel({ parentTitle: 'Evidence' })

    expect(wrapper.find('button[aria-label="Back to Evidence"]').exists()).toBe(true)
  })
})

describe('SidePanel closing', () => {
  it('emits close when the close control is activated', async () => {
    const wrapper = mountPanel()

    await wrapper.get('button[aria-label="Close"]').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('emits back when the back control is activated', async () => {
    const wrapper = mountPanel({ parentTitle: 'Evidence' })

    await wrapper.get('button[aria-label="Back to Evidence"]').trigger('click')

    expect(wrapper.emitted('back')).toHaveLength(1)
  })

  it('emits close when Escape is pressed inside the sheet', async () => {
    const wrapper = mountPanel()

    await wrapper.get('[role]').trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('emits request-close instead of close on Escape when the input is changed', async () => {
    const wrapper = mountPanel({ dirty: true })

    await wrapper.get('[role]').trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('request-close'), 'the parent decides').toHaveLength(1)
    expect(wrapper.emitted('close'), 'nothing closes yet').toBeUndefined()
  })

  it('emits request-close instead of close from the close control when the input is changed', async () => {
    const wrapper = mountPanel({ dirty: true })

    await wrapper.get('button[aria-label="Close"]').trigger('click')

    expect(wrapper.emitted('request-close')).toHaveLength(1)
    expect(wrapper.emitted('close')).toBeUndefined()
  })
})

describe('SidePanel modal semantics by size class', () => {
  it('is a modal dialog with aria-modal on compact width', () => {
    const attributes = mountPanel({ sizeClass: 'compact' }).get('[role="dialog"]').attributes()

    expect(attributes['aria-modal']).toBe('true')
  })

  it.each(['medium', 'expanded'])('is a complementary region without aria-modal on %s width', (sizeClass) => {
    const wrapper = mountPanel({ sizeClass })

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.get('[role="complementary"]').attributes('aria-modal')).toBeUndefined()
  })

  it.each([
    ['compact', 'w-full'],
    ['medium', 'w-[400px]'],
    ['expanded', 'w-[480px]'],
  ])('is %s width class %s (the MD3 side sheet widths)', (sizeClass, expectedClass) => {
    const wrapper = mountPanel({ sizeClass })

    expect(wrapper.get('[role]').classes()).toContain(expectedClass)
  })
})

describe('SidePanel focus', () => {
  it('moves focus to its first control when it opens', () => {
    const wrapper = mountPanel({ parentTitle: 'Evidence' })

    expect(document.activeElement).toBe(wrapper.get('button[aria-label="Back to Evidence"]').element)
  })

  it('returns focus to the control that opened it when it closes', () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const wrapper = mountPanel()

    wrapper.unmount()
    mounted.splice(mounted.indexOf(wrapper), 1)

    expect(document.activeElement).toBe(opener)
  })

  it('keeps Tab inside the sheet on compact width (focus trap)', async () => {
    const wrapper = mountPanel({ sizeClass: 'compact' }, () => [h('button', { type: 'button' }, 'Save')])
    const save = wrapper.get('button:not([aria-label="Close"])').element as HTMLElement
    const close = wrapper.get('button[aria-label="Close"]').element as HTMLElement
    save.focus()

    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    wrapper.get('[role="dialog"]').element.dispatchEvent(event)

    expect(event.defaultPrevented, 'the browser must not move focus out').toBe(true)
    expect(document.activeElement, 'focus wraps to the first control').toBe(close)
  })

  it('does not trap Tab on expanded width, so the page behind stays reachable', () => {
    const wrapper = mountPanel({ sizeClass: 'expanded' }, () => [h('button', { type: 'button' }, 'Save')])
    const save = wrapper.get('button:not([aria-label="Close"])').element as HTMLElement
    save.focus()

    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    wrapper.get('[role="complementary"]').element.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(false)
  })
})

describe('SidePanel motion', () => {
  it('slides in with the side-sheet transition', async () => {
    stubReducedMotion(false)
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('transition-stub').attributes('name')).toBe('md-side-sheet')
  })

  it('falls back to a plain fade under reduced motion, with no transform', async () => {
    stubReducedMotion(true)
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('transition-stub').attributes('name')).toBe('md-fade')
  })
})
