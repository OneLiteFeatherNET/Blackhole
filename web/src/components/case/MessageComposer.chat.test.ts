import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ApiResult } from '../../api/types'
import { mountWithApp, ok } from '../../test/mount'
import MessageComposer from './MessageComposer.vue'

function setup(options: { chat?: boolean } = {}) {
  const save = vi.fn(async (..._args: unknown[]): Promise<ApiResult<unknown>> => ok({ messageId: 'm', seq: 1, repeated: false }))
  return {
    save,
    mount: () =>
      mountWithApp(MessageComposer, {
        props: { label: 'Reply to the player', submitLabel: 'Send', save, ...(options.chat === undefined ? {} : { chat: options.chat }) },
      }),
  }
}

/** Dispatches a real keyboard event, so the test can see whether the composer cancelled its default action. */
function press(element: Element, init: KeyboardEventInit & { isComposing?: boolean }): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { cancelable: true, bubbles: true, ...init })
  if (init.isComposing) Object.defineProperty(event, 'isComposing', { value: true })
  element.dispatchEvent(event)
  return event
}

describe('MessageComposer: the reply composer', () => {
  it('sends on Enter', async () => {
    const { save, mount } = setup({ chat: true })
    const { wrapper } = await mount()
    const field = wrapper.get('[data-testid="message-text"]')

    await field.setValue('Thanks, we checked.')
    const event = press(field.element, { key: 'Enter' })

    expect(event.defaultPrevented, 'the newline is not added').toBe(true)
    expect(save).toHaveBeenCalledOnce()
    expect(save.mock.calls[0]![0]).toBe('Thanks, we checked.')

    wrapper.unmount()
  })

  it('adds a line on Shift+Enter and sends nothing', async () => {
    const { save, mount } = setup({ chat: true })
    const { wrapper } = await mount()
    const field = wrapper.get('[data-testid="message-text"]')

    await field.setValue('first line')
    const event = press(field.element, { key: 'Enter', shiftKey: true })

    expect(event.defaultPrevented, 'the browser adds the newline').toBe(false)
    expect(save).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('does nothing on Enter while an input method is composing text', async () => {
    const { save, mount } = setup({ chat: true })
    const { wrapper } = await mount()
    const field = wrapper.get('[data-testid="message-text"]')

    await field.setValue('ni')
    const event = press(field.element, { key: 'Enter', isComposing: true })

    expect(event.defaultPrevented).toBe(false)
    expect(save).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('still sends with Ctrl+Enter and with Cmd+Enter', async () => {
    const { save, mount } = setup({ chat: true })
    const { wrapper } = await mount()
    const field = wrapper.get('[data-testid="message-text"]')

    await field.setValue('quick')
    press(field.element, { key: 'Enter', ctrlKey: true })
    expect(save).toHaveBeenCalledTimes(1)
    await flushPromises()
    await field.setValue('again')
    press(field.element, { key: 'Enter', metaKey: true })

    expect(save).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('has two rows and names the keys in its hint', async () => {
    const { mount } = setup({ chat: true })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="message-text"]').attributes('rows')).toBe('2')
    expect(wrapper.get('[data-testid="message-counter"]').element.parentElement?.textContent).toContain('Enter sends, Shift+Enter adds a line')

    wrapper.unmount()
  })
})

describe('MessageComposer: the edit composer', () => {
  it('keeps Enter as a new line and does not send', async () => {
    const { save, mount } = setup()
    const { wrapper } = await mount()
    const field = wrapper.get('[data-testid="message-text"]')

    const event = press(field.element, { key: 'Enter' })

    expect(event.defaultPrevented).toBe(false)
    expect(save).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="message-text"]').attributes('rows')).toBe('4')

    wrapper.unmount()
  })

  it('still sends with Ctrl+Enter', async () => {
    const { save, mount } = setup()
    const { wrapper } = await mount()
    const field = wrapper.get('[data-testid="message-text"]')

    await field.setValue('fixed')
    press(field.element, { key: 'Enter', ctrlKey: true })

    expect(save).toHaveBeenCalledOnce()

    wrapper.unmount()
  })
})
