import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'
import MdDialog from './MdDialog.vue'

describe('MdDialog', () => {
  const mounted: { unmount: () => void }[] = []

  afterEach(() => {
    mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('renders a labelled modal dialog only while open and moves focus into it', async () => {
    const wrapper = mount(MdDialog, {
      props: { open: false, headline: 'Strafe aufheben' },
      attachTo: document.body,
    })
    mounted.push(wrapper)

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)

    await wrapper.setProps({ open: true })
    await flushPromises()

    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(dialog.attributes('aria-labelledby')).toBe(wrapper.get('h2').attributes('id'))
    expect(wrapper.get('h2').text()).toBe('Strafe aufheben')
    expect(document.activeElement).toBe(dialog.element)
  })

  it('takes focus when it is mounted already open', async () => {
    const wrapper = mount(MdDialog, {
      props: { open: true, headline: 'Bestätigen' },
      attachTo: document.body,
    })
    mounted.push(wrapper)
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)
  })

  it('emits close when Escape is pressed', async () => {
    const wrapper = mount(MdDialog, {
      props: { open: true, headline: 'Bestätigen' },
      attachTo: document.body,
    })
    mounted.push(wrapper)

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('closes only the dialog the Escape key was pressed in when dialogs are nested', async () => {
    const Outer = defineComponent({
      components: { MdDialog },
      emits: ['outer', 'inner'],
      template: `<MdDialog open headline="outer" @close="$emit('outer')"><MdDialog open headline="inner" @close="$emit('inner')" /></MdDialog>`,
    })
    const wrapper = mount(Outer, { attachTo: document.body })
    mounted.push(wrapper)

    await wrapper.findAll('[role="dialog"]')[1]!.trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('inner')).toHaveLength(1)
    expect(wrapper.emitted('outer'), 'the outer dialog stays open').toBeUndefined()
  })

  it('leaves the field background to the surface token instead of setting it inline', async () => {
    const wrapper = mount(MdDialog, { props: { open: true, headline: 'x' }, attachTo: document.body })
    mounted.push(wrapper)

    const panel = wrapper.get('[role="dialog"]')

    expect(panel.classes()).toContain('bg-surface-container-high')
    expect(panel.attributes('style'), 'no inline --md-field-bg').toBeUndefined()
  })

  it('draws the scrim with the scrim token, not a fixed opacity', () => {
    const wrapper = mount(MdDialog, { props: { open: true, headline: 'x' }, attachTo: document.body })
    mounted.push(wrapper)

    const scrim = wrapper.get('[role="dialog"]').element.parentElement!.querySelector('div.absolute')!

    expect(scrim.className).toContain('bg-scrim/(--md-sys-scrim-opacity)')
    expect(scrim.className).not.toContain('bg-scrim/[0.32]')
  })
})
