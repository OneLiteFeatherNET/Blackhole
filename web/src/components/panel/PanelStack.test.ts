import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h, type Component } from 'vue'
import { i18n } from '../../i18n'
import { fakeMatchMedia } from '../../test/media'
import PanelStack from './PanelStack.vue'
import SidePanel from './SidePanel.vue'
import type { PanelToken } from './panelState'
import { useWindowClass, type WindowClass } from './useWindowClass'

const mounted: VueWrapper[] = []
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

/** A registry entry: a sheet that wraps itself in SidePanel and titles itself with its target. */
const Sheet: Component = defineComponent({
  props: {
    target: { type: String, required: true },
    sizeClass: { type: String, default: 'expanded' },
    covered: { type: Boolean, default: false },
  },
  emits: ['close', 'back', 'request-close', 'reveal'],
  setup(props, { emit }) {
    return () =>
      h(
        SidePanel,
        {
          title: props.target,
          sizeClass: props.sizeClass as WindowClass,
          covered: props.covered,
          onClose: () => emit('close'),
          onReveal: () => emit('reveal'),
        },
        () => [h('button', { type: 'button' }, `Save ${props.target}`)],
      )
  },
})

function mountStack(tokens: PanelToken[], options: { registry?: Record<string, Component>; sizeClass?: WindowClass } = {}) {
  i18n.global.locale.value = 'en'
  const wrapper = mount(PanelStack, {
    props: {
      tokens,
      registry: options.registry ?? { 'client-rule': Sheet, evidence: Sheet },
      sizeClass: options.sizeClass ?? 'expanded',
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
  mounted.push(wrapper)
  return wrapper
}

describe('PanelStack', () => {
  it('renders one sheet per token, in the order of the URL (parent first)', () => {
    const wrapper = mountStack([
      { kind: 'evidence', target: 'CASE-12' },
      { kind: 'client-rule', target: 'new' },
    ])

    expect(wrapper.findAll('h2').map((heading) => heading.text())).toEqual(['CASE-12', 'new'])
  })

  it('closes only the top sheet when Escape is pressed outside the sheets', async () => {
    const wrapper = mountStack([
      { kind: 'evidence', target: 'CASE-12' },
      { kind: 'client-rule', target: 'new' },
    ])

    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))

    expect(wrapper.emitted('close-top'), 'exactly one sheet closes').toHaveLength(1)
  })

  it('closes the top sheet once when Escape is pressed inside it', async () => {
    const wrapper = mountStack([
      { kind: 'evidence', target: 'CASE-12' },
      { kind: 'client-rule', target: 'new' },
    ])

    await wrapper.findAll('[role="complementary"]')[1]!.trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('close-top')).toHaveLength(1)
  })

  it('covers the parent with a strip on expanded width, and activating the strip closes the child', async () => {
    const wrapper = mountStack(
      [
        { kind: 'evidence', target: 'CASE-12' },
        { kind: 'client-rule', target: 'new' },
      ],
      { sizeClass: 'expanded' },
    )

    expect(wrapper.find('button[aria-label="Back to CASE-12"]').exists(), 'the parent shows as a strip').toBe(true)
    await wrapper.get('button[aria-label="Back to CASE-12"]').trigger('click')

    expect(wrapper.emitted('close-top'), 'the child is closed, the parent is top again').toHaveLength(1)
  })

  it('renders nothing for a kind the registry does not have, and does not throw', () => {
    const wrapper = mountStack([{ kind: 'client-rule', target: 'new' }], { registry: {} })

    expect(wrapper.findAll('h2')).toHaveLength(0)
  })

  it('passes the size class to every sheet', () => {
    const wrapper = mountStack([{ kind: 'client-rule', target: 'new' }], { sizeClass: 'compact' })

    expect(wrapper.get('[role="dialog"]').attributes('aria-modal')).toBe('true')
  })
})

describe('PanelStack at the widths checked for the client rules page', () => {
  /** A page that measures its window and passes the class to the stack, as the real view will. */
  function mountAtWidth(width: number) {
    const media = fakeMatchMedia({ value: width })
    const Page = defineComponent({
      setup() {
        const sizeClass = useWindowClass({ matchMedia: media.matchMedia })
        return () => h(PanelStack, { tokens: [{ kind: 'client-rule', target: 'new' }], registry: { 'client-rule': Sheet }, sizeClass: sizeClass.value })
      },
    })
    i18n.global.locale.value = 'en'
    const wrapper = mount(Page, { global: { plugins: [i18n] }, attachTo: document.body })
    mounted.push(wrapper)
    return wrapper
  }

  it('shows a full-width modal sheet at 480 px', () => {
    const wrapper = mountAtWidth(480)

    expect(wrapper.get('[role="dialog"]').classes()).toContain('w-full')
  })

  it('shows a 400 px standard sheet at 720 px', () => {
    const wrapper = mountAtWidth(720)

    expect(wrapper.get('[role="complementary"]').classes()).toContain('w-[400px]')
  })

  it('shows a 480 px standard sheet at 1280 px and keeps the page behind it usable', () => {
    const wrapper = mountAtWidth(1280)

    expect(wrapper.get('[role="complementary"]').classes()).toContain('w-[480px]')
    expect(wrapper.find('[aria-modal]').exists()).toBe(false)
  })
})
