import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
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

  it('hands its own surface color to outlined fields so their label notch matches the dialog', async () => {
    const wrapper = mount(MdDialog, { props: { open: true, headline: 'x' }, attachTo: document.body })
    mounted.push(wrapper)

    const panel = wrapper.get('[role="dialog"]')

    expect(panel.classes()).toContain('bg-surface-container-high')
    expect(panel.attributes('style')).toContain('--md-field-bg: var(--color-surface-container-high)')
  })
})
