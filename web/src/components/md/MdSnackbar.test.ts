import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MdSnackbar from './MdSnackbar.vue'

describe('MdSnackbar', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('announces the message in a live region and emits action when the action is pressed', async () => {
    const wrapper = mount(MdSnackbar, {
      props: { open: true, message: 'Gespeichert', actionLabel: 'Rückgängig' },
    })

    expect(wrapper.get('[role="status"]').text()).toContain('Gespeichert')

    await wrapper.get('button').trigger('click')

    expect(wrapper.emitted('action')).toHaveLength(1)
    wrapper.unmount()
  })

  it('emits close after its timeout so the parent can hide it', () => {
    const wrapper = mount(MdSnackbar, { props: { open: true, message: 'Gespeichert' } })

    vi.advanceTimersByTime(5999)
    expect(wrapper.emitted('close')).toBeUndefined()

    vi.advanceTimersByTime(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('keeps the message for a longer time when the caller asks for one, such as an undo window', () => {
    const wrapper = mount(MdSnackbar, { props: { open: true, message: 'Zugewiesen', actionLabel: 'Rückgängig', autoCloseMs: 10_000 } })

    vi.advanceTimersByTime(9999)
    expect(wrapper.emitted('close')).toBeUndefined()

    vi.advanceTimersByTime(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
