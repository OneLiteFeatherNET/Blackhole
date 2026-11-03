import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, type PropType } from 'vue'
import { useRoute } from 'vue-router'
import { anchorDomId, anchorIdOf } from '../components/case/itemAnchor'
import { mountWithApp } from '../test/mount'
import { stubReducedMotion } from '../test/reducedMotion'
import { useItemHighlight } from './useItemHighlight'

/** A list of items with the anchor ids of the case page; the highlight is read from the rendered attribute. */
const Probe = defineComponent({
  props: { items: { type: Array as PropType<string[]>, required: true } },
  setup(props) {
    const route = useRoute()
    const { highlighted } = useItemHighlight({ anchor: () => anchorIdOf(route.hash), ready: () => props.items })
    return () =>
      h(
        'ol',
        props.items.map((id) =>
          h('li', { key: id, id: anchorDomId(id), tabindex: -1, 'data-testid': `item-${id}`, 'data-highlighted': highlighted.value === id ? 'true' : undefined }, id),
        ),
      )
  },
})

describe('useItemHighlight', () => {
  let scrollIntoView: ReturnType<typeof vi.fn>
  const original = Element.prototype.scrollIntoView

  beforeEach(() => {
    scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView as unknown as typeof original
  })

  afterEach(() => {
    Element.prototype.scrollIntoView = original
    vi.unstubAllGlobals()
  })

  it('scrolls the item the address names into view and focuses it', async () => {
    const { wrapper } = await mountWithApp(Probe, { path: '/cases/p-0001?tab=audit#event-b', props: { items: ['a', 'b', 'c'] } })

    expect(scrollIntoView).toHaveBeenCalledTimes(1)
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'center', behavior: 'smooth' })
    expect(wrapper.get('[data-testid="item-b"]').attributes('data-highlighted')).toBe('true')
    expect(document.activeElement?.id).toBe('event-b')

    wrapper.unmount()
  })

  it('scrolls without animation when the system asks for reduced motion', async () => {
    stubReducedMotion(true)
    const { wrapper } = await mountWithApp(Probe, { path: '/x#event-a', props: { items: ['a'] } })

    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'center', behavior: 'auto' })

    wrapper.unmount()
  })

  it('highlights nothing and does not scroll when the address names an item that is not on the page', async () => {
    const { wrapper } = await mountWithApp(Probe, { path: '/x#event-zz', props: { items: ['a', 'b'] } })

    expect(scrollIntoView).not.toHaveBeenCalled()
    expect(wrapper.findAll('[data-highlighted="true"]')).toHaveLength(0)

    wrapper.unmount()
  })

  it('highlights nothing for an address without a fragment', async () => {
    const { wrapper } = await mountWithApp(Probe, { path: '/x?tab=audit', props: { items: ['a'] } })

    expect(scrollIntoView).not.toHaveBeenCalled()
    expect(wrapper.findAll('[data-highlighted="true"]')).toHaveLength(0)

    wrapper.unmount()
  })

  it('waits for an item that arrives with a later load, then highlights it', async () => {
    const { wrapper } = await mountWithApp(Probe, { path: '/x#event-c', props: { items: ['a'] } })
    expect(scrollIntoView).not.toHaveBeenCalled()

    await wrapper.setProps({ items: ['a', 'b', 'c'] })
    await flushPromises()

    expect(scrollIntoView).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="item-c"]').attributes('data-highlighted')).toBe('true')

    wrapper.unmount()
  })

  it('keeps the highlight while the reader stays on the item, and clears it when they click elsewhere', async () => {
    const { wrapper } = await mountWithApp(Probe, { path: '/x#event-a', props: { items: ['a', 'b'] } })
    const item = wrapper.get('[data-testid="item-a"]').element

    item.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()
    expect(wrapper.get('[data-testid="item-a"]').attributes('data-highlighted')).toBe('true')

    const elsewhere = wrapper.get('[data-testid="item-b"]').element
    elsewhere.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()
    expect(wrapper.findAll('[data-highlighted="true"]')).toHaveLength(0)

    wrapper.unmount()
  })

  it('clears the highlight when the reader navigates to another address', async () => {
    const { wrapper, router } = await mountWithApp(Probe, { path: '/x#event-a', props: { items: ['a', 'b'] } })

    await router.push('/y')
    await nextTick()

    expect(wrapper.findAll('[data-highlighted="true"]')).toHaveLength(0)

    wrapper.unmount()
  })
})
