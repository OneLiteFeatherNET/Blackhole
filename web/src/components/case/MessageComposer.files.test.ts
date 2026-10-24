import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ApiResult, ChatAttachment } from '../../api/types'
import { fail, mountWithApp, ok } from '../../test/mount'
import MessageComposer from './MessageComposer.vue'

const png = (name = 'shot.png', bytes = 4) => {
  const file = new File(['x'], name, { type: 'image/png' })
  Object.defineProperty(file, 'size', { value: bytes })
  return file
}

let counter = 0
const staged = (name: string): ChatAttachment => ({ attachmentId: `att-${++counter}`, name, contentType: 'image/png', size: 4 })

function setup(options: { upload?: (file: File) => Promise<ApiResult<ChatAttachment>>; save?: (...args: unknown[]) => Promise<ApiResult<unknown>> } = {}) {
  const upload = vi.fn(options.upload ?? (async (file: File) => ok(staged(file.name))))
  const save = vi.fn(options.save ?? (async () => ok({ messageId: 'm', seq: 1, repeated: false })))
  return {
    upload,
    save,
    mount: () =>
      mountWithApp(MessageComposer, {
        props: { label: 'Reply to the player', submitLabel: 'Send', save, upload },
      }),
  }
}

async function attach(wrapper: { get: (selector: string) => { element: Element; trigger: (name: string) => Promise<void> } }, files: File[]) {
  const input = wrapper.get('[data-testid="file-input"]')
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
  await flushPromises()
}

describe('MessageComposer: attaching files', () => {
  it('shows the attach control, a counter and the formatting hint, and no preview', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="attach"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="file-counter"]').text()).toBe('0 / 5 files')
    expect(wrapper.get('[data-testid="format-hint"]').text()).toContain('**bold**')
    expect(wrapper.find('[data-testid="preview"]').exists(), 'no live preview').toBe(false)
    wrapper.unmount()
  })

  it('stages a chosen file at once and shows it as ready', async () => {
    const { mount, upload } = setup()
    const { wrapper } = await mount()

    await attach(wrapper as never, [png('shot.png')])

    expect(upload).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="chip"]').text()).toContain('shot.png')
    expect(wrapper.get('[data-testid="chip"]').attributes('data-state')).toBe('ready')
    expect(wrapper.get('[data-testid="file-counter"]').text()).toBe('1 / 5 files')
    wrapper.unmount()
  })

  it('accepts files dropped onto the composer', async () => {
    const { mount, upload } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="composer-drop"]').trigger('drop', { dataTransfer: { files: [png('dropped.png')] } })
    await flushPromises()

    expect(upload).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('dropped.png')
    wrapper.unmount()
  })

  it('accepts a pasted image', async () => {
    const { mount, upload } = setup()
    const { wrapper } = await mount()
    const file = png('pasted.png')

    await wrapper.get('[data-testid="message-text"]').trigger('paste', {
      clipboardData: { items: [{ kind: 'file', getAsFile: () => file }], files: [file] },
    })
    await flushPromises()

    expect(upload).toHaveBeenCalledWith(file)
    wrapper.unmount()
  })

  it('refuses a type the backend does not accept without asking the server', async () => {
    const { mount, upload } = setup()
    const { wrapper } = await mount()
    const gif = new File(['x'], 'anim.gif', { type: 'image/gif' })

    await attach(wrapper as never, [gif])

    expect(upload).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="chip"]').text()).toContain('This file type is not allowed')
    wrapper.unmount()
  })

  it('refuses a file larger than 10 MB without asking the server', async () => {
    const { mount, upload } = setup()
    const { wrapper } = await mount()

    await attach(wrapper as never, [png('huge.png', 10 * 1024 * 1024 + 1)])

    expect(upload).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="chip"]').text()).toContain('The file is larger than 10 MB')
    wrapper.unmount()
  })

  it('keeps at most five files and says so', async () => {
    const { mount, upload } = setup()
    const { wrapper } = await mount()

    await attach(wrapper as never, Array.from({ length: 6 }, (_, index) => png(`${index}.png`)))

    expect(upload).toHaveBeenCalledTimes(5)
    expect(wrapper.findAll('[data-testid="chip"]')).toHaveLength(5)
    expect(wrapper.get('[data-testid="message-error"]').text()).toContain('at most 5')
    wrapper.unmount()
  })

  it('shows the server answer for a refused file and keeps the other files', async () => {
    const { mount } = setup({
      upload: async (file) => (file.name === 'bad.png' ? fail('The file could not be accepted', 422, 'VALIDATION') : ok(staged(file.name))),
    })
    const { wrapper } = await mount()

    await attach(wrapper as never, [png('bad.png'), png('good.png')])

    const chips = wrapper.findAll('[data-testid="chip"]')
    expect(chips[0]!.attributes('data-state')).toBe('failed')
    expect(chips[0]!.text()).toContain('The file could not be accepted')
    expect(chips[1]!.attributes('data-state')).toBe('ready')
    wrapper.unmount()
  })

  it('removes a file without a request', async () => {
    const { mount, upload } = setup()
    const { wrapper } = await mount()
    await attach(wrapper as never, [png('gone.png')])

    await wrapper.get('[data-testid="chip-remove"]').trigger('click')

    expect(wrapper.find('[data-testid="chip"]').exists()).toBe(false)
    expect(upload).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('warns before the player sees the files', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await attach(wrapper as never, [png()])

    expect(wrapper.get('[data-testid="file-warning"]').text()).toContain('The player will see these files')
    wrapper.unmount()
  })

  it('draws no file controls for an edit', async () => {
    const { save } = setup()
    const { wrapper } = await mountWithApp(MessageComposer, { props: { label: 'Edit', submitLabel: 'Save', save, initial: 'old', cancellable: true } })

    expect(wrapper.find('[data-testid="attach"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('MessageComposer: sending with files', () => {
  it('keeps Send disabled while a file is still uploading', async () => {
    let finish: (value: ApiResult<ChatAttachment>) => void = () => {}
    const { mount } = setup({ upload: () => new Promise((resolve) => (finish = resolve)) })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="message-text"]').setValue('hello')

    await attach(wrapper as never, [png()])

    expect(wrapper.get('[data-testid="chip"]').attributes('data-state')).toBe('uploading')
    expect(wrapper.get('[data-testid="message-submit"]').attributes('disabled')).toBeDefined()

    finish(ok(staged('shot.png')))
    await flushPromises()

    expect(wrapper.get('[data-testid="message-submit"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('keeps Send disabled while a refused file is still in the list', async () => {
    const { mount } = setup({ upload: async () => fail('Refused', 422, 'VALIDATION') })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="message-text"]').setValue('hello')

    await attach(wrapper as never, [png()])

    expect(wrapper.get('[data-testid="message-submit"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('sends the text with the ready attachment ids and clears the composer', async () => {
    const { mount, save } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="message-text"]').setValue('see the screenshot')
    await attach(wrapper as never, [png('one.png'), png('two.png')])

    await wrapper.get('[data-testid="message-submit"]').trigger('click')
    await flushPromises()

    expect(save).toHaveBeenCalledWith('see the screenshot', expect.any(String), expect.any(Array))
    expect(save.mock.calls[0]![2]).toHaveLength(2)
    expect(wrapper.find('[data-testid="chip"]').exists()).toBe(false)
    expect((wrapper.get('[data-testid="message-text"]').element as HTMLTextAreaElement).value).toBe('')
    wrapper.unmount()
  })

  it('sends a file with no text', async () => {
    const { mount, save } = setup()
    const { wrapper } = await mount()
    await attach(wrapper as never, [png()])

    await wrapper.get('[data-testid="message-submit"]').trigger('click')
    await flushPromises()

    expect(save).toHaveBeenCalledTimes(1)
    expect(save.mock.calls[0]![0]).toBe('')
    wrapper.unmount()
  })

  it('keeps the files and the idempotency key when a send fails, so a retry cannot post twice', async () => {
    const { mount, save } = setup({ save: async () => fail('The message could not be saved', 500, 'UNKNOWN') })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="message-text"]').setValue('hello')
    await attach(wrapper as never, [png()])

    await wrapper.get('[data-testid="message-submit"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="message-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="chip"]').exists()).toBe(true)
    expect(save.mock.calls[0]![1]).toBe(save.mock.calls[1]![1])
    wrapper.unmount()
  })

  it('does not send an empty message without files', async () => {
    const { mount, save } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="message-submit"]').trigger('click')
    await flushPromises()

    expect(save).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="message-error"]').text()).toBe('Write a message first.')
    wrapper.unmount()
  })
})
