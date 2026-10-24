import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { StagedAttachment } from '../api/contract'
import type { PortalApi } from '../api/portal'
import ChatComposer from './ChatComposer.vue'
import { fail, fakeApi, mountPortal, ok } from '../test/helpers'

const STAGED: StagedAttachment = { attachmentId: '3f2b8c1e-aaaa-4bbb-8ccc-0123456789ab', name: 'shot.png', contentType: 'image/png', size: 2048 }

let mounted: Awaited<ReturnType<typeof mountPortal>> | undefined

afterEach(() => {
  mounted?.wrapper.unmount()
  mounted = undefined
})

async function composer(methods: Partial<PortalApi> = {}) {
  mounted = await mountPortal(fakeApi(methods), { component: ChatComposer })
  return mounted
}

function pick(files: File[]) {
  const input = mounted!.wrapper.get('[data-testid="file-input"]')
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  return input.trigger('change')
}

function pdf(name = 'appeal.pdf') {
  return new File(['%PDF-1.4'], name, { type: 'application/pdf' })
}

function png(name = 'shot.png') {
  return new File(['png'], name, { type: 'image/png' })
}

/** A promise the test resolves itself, so the state in between can be seen. */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => (resolve = done))
  return { promise, resolve }
}

describe('text and hint', () => {
  it('labels the message field and counts its characters', async () => {
    const { wrapper } = await composer()

    expect(wrapper.get('textarea').attributes('id')).toBe('message')
    expect(wrapper.get('label[for="message"]').text()).toBe('Your message')
    expect(wrapper.get('#message-count').text()).toContain('0 of 2000 characters')
  })

  it('shows the formatting hint under the composer and promises no preview', async () => {
    const { wrapper } = await composer()

    const hint = wrapper.get('#message-hint').text()
    expect(hint).toContain('**bold**')
    expect(hint).toContain('no preview')
    expect(wrapper.findAll('[data-testid="preview"]')).toHaveLength(0)
  })
})

describe('attaching', () => {
  it('uploads a chosen file and shows it as ready to send', async () => {
    const upload = vi.fn(async () => ok(STAGED))
    const { wrapper } = await composer({ uploadAttachment: upload })

    await pick([png()])
    await flushPromises()

    expect(upload).toHaveBeenCalledTimes(1)
    const chip = wrapper.get('[data-testid="chips"] li')
    expect(chip.text()).toContain('shot.png')
    expect(chip.text()).toContain('Ready to send')
    expect(wrapper.get('#file-count').text()).toBe('1 of 5 files')
  })

  it('refuses a file of the wrong type at once, without an upload', async () => {
    const upload = vi.fn(async () => ok(STAGED))
    const { wrapper } = await composer({ uploadAttachment: upload })

    await pick([new File(['<svg onload=x()>'], 'vector.svg', { type: 'image/svg+xml' })])
    await flushPromises()

    expect(upload).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('This file type is not allowed')
    expect(wrapper.findAll('[data-testid="chips"] li')).toHaveLength(0)
  })

  it('shows the server refusal on the chip, in words', async () => {
    const { wrapper } = await composer({ uploadAttachment: async () => fail('FILE_FLAGGED', 422) })

    await pick([pdf()])
    await flushPromises()

    expect(wrapper.get('[data-testid="chips"] li').text()).toContain('The file could not be accepted.')
  })

  it('shows a generic refusal for a code it does not know', async () => {
    const { wrapper } = await composer({ uploadAttachment: async () => fail('SOMETHING_NEW' as never, 422) })

    await pick([pdf()])
    await flushPromises()

    expect(wrapper.get('[data-testid="chips"] li').text()).toContain('The file could not be accepted.')
  })

  it('refuses a sixth file in the same message', async () => {
    const upload = vi.fn(async () => ok(STAGED))
    const { wrapper } = await composer({ uploadAttachment: upload })

    await pick([png('1.png'), png('2.png'), png('3.png'), png('4.png'), png('5.png')])
    await flushPromises()
    await pick([png('6.png')])
    await flushPromises()

    expect(upload).toHaveBeenCalledTimes(5)
    expect(wrapper.get('[role="alert"]').text()).toContain('at most 5 files')
  })

  it('attaches files dropped onto the thread and shows a drop target while dragging', async () => {
    const upload = vi.fn(async () => ok(STAGED))
    const { wrapper } = await composer({ uploadAttachment: upload })
    const form = wrapper.get('form')

    const over = new Event('dragover', { bubbles: true, cancelable: true }) as DragEvent
    Object.defineProperty(over, 'dataTransfer', { value: { types: ['Files'] } })
    form.element.dispatchEvent(over)
    await flushPromises()
    expect(wrapper.find('[data-testid="drop-target"]').exists()).toBe(true)

    const drop = new Event('drop', { bubbles: true, cancelable: true }) as DragEvent
    Object.defineProperty(drop, 'dataTransfer', { value: { types: ['Files'], files: [png()] } })
    form.element.dispatchEvent(drop)
    await flushPromises()

    expect(upload).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="drop-target"]').exists()).toBe(false)
  })

  it('attaches files pasted from the clipboard and leaves pasted text alone', async () => {
    const upload = vi.fn(async () => ok(STAGED))
    const { wrapper } = await composer({ uploadAttachment: upload })
    const textarea = wrapper.get('textarea')

    const withFile = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent
    Object.defineProperty(withFile, 'clipboardData', { value: { files: [png('pasted.png')] } })
    textarea.element.dispatchEvent(withFile)
    await flushPromises()

    const withText = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent
    Object.defineProperty(withText, 'clipboardData', { value: { files: [] } })
    textarea.element.dispatchEvent(withText)
    await flushPromises()

    expect(upload).toHaveBeenCalledTimes(1)
    expect(withText.defaultPrevented).toBe(false)
  })

  it('removes a ready file and asks the backend to delete the staged copy', async () => {
    const deleted = vi.fn(async () => ok(null))
    const { wrapper } = await composer({ uploadAttachment: async () => ok(STAGED), deleteAttachment: deleted })
    await pick([png()])
    await flushPromises()

    await wrapper.get('button[aria-label="Remove shot.png"]').trigger('click')
    await flushPromises()

    expect(deleted).toHaveBeenCalledWith(STAGED.attachmentId)
    expect(wrapper.findAll('[data-testid="chips"] li')).toHaveLength(0)
  })

  it('removes a file that is still uploading and deletes its copy once it lands', async () => {
    const pending = deferred<ReturnType<typeof ok<StagedAttachment>>>()
    const deleted = vi.fn(async () => ok(null))
    const { wrapper } = await composer({ uploadAttachment: () => pending.promise, deleteAttachment: deleted })
    await pick([png()])

    await wrapper.get('button[aria-label="Remove shot.png"]').trigger('click')
    pending.resolve(ok(STAGED))
    await flushPromises()

    expect(deleted).toHaveBeenCalledWith(STAGED.attachmentId)
  })
})

describe('sending gate', () => {
  it('keeps Send disabled while a file is uploading and enables it when the file is ready', async () => {
    const pending = deferred<ReturnType<typeof ok<StagedAttachment>>>()
    const { wrapper } = await composer({ uploadAttachment: () => pending.promise })
    await pick([png()])
    await flushPromises()

    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[role="status"]').text()).toContain('Uploading')

    pending.resolve(ok(STAGED))
    await flushPromises()

    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })

  it('sends with a file alone, no text needed', async () => {
    const { wrapper, session } = await composer({ uploadAttachment: async () => ok(STAGED) })
    await pick([png()])
    await flushPromises()

    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('send')).toHaveLength(1)
    expect(session.state.drafts.files[0]?.attachmentId).toBe(STAGED.attachmentId)
  })

  it('does not send an empty message', async () => {
    const { wrapper } = await composer()

    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('send')).toBeUndefined()
  })

  it('sends with Ctrl+Enter when sending is possible', async () => {
    const { wrapper, session } = await composer()
    session.state.drafts.message = 'hello'
    await flushPromises()

    await wrapper.get('textarea').trigger('keydown', { key: 'Enter', ctrlKey: true })

    expect(wrapper.emitted('send')).toHaveLength(1)
  })

  it('shows the failure it is given, in an assertive alert', async () => {
    const { wrapper } = await composer()
    await wrapper.setProps({ failure: 'Please try again.' })

    expect(wrapper.get('[role="alert"][aria-live="assertive"]').text()).toBe('Please try again.')
  })
})

describe('labels and live regions', () => {
  it('names the attach button and the file list', async () => {
    const { wrapper } = await composer({ uploadAttachment: async () => ok(STAGED) })
    await pick([png()])
    await flushPromises()

    expect(wrapper.get('button[aria-describedby="file-count"]').text()).toBe('Attach files')
    expect(wrapper.get('[data-testid="chips"]').attributes('aria-label')).toBe('Attached files')
  })

  it('keeps the file picker out of the tab order', async () => {
    const { wrapper } = await composer()

    expect(wrapper.get('[data-testid="file-input"]').classes()).toContain('hidden')
  })
})
