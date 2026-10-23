import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ApiResult } from '../../api/types'
import { i18n } from '../../i18n'
import { fail, ok } from '../../test/mount'
import NoteEditor from './NoteEditor.vue'

function setup(save: (text: string) => Promise<ApiResult<unknown>>, props: Record<string, unknown> = {}) {
  i18n.global.locale.value = 'en'
  const wrapper = mount(NoteEditor, { props: { label: 'New note', submitLabel: 'Add note', save, ...props }, global: { plugins: [i18n] } })
  const type = (text: string) => wrapper.get('[data-testid="note-text"]').setValue(text)
  const submit = async () => {
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    await flushPromises()
  }
  return { wrapper, type, submit }
}

describe('NoteEditor: validation', () => {
  it('does not send an empty or blank note', async () => {
    const save = vi.fn(async (_text: string) => ok({}))
    const { wrapper, type, submit } = setup(save)

    await submit()
    await type('   ')
    await submit()

    expect(save).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="note-error"]').text()).toBe('Write a note first.')

    wrapper.unmount()
  })

  it('counts the characters and refuses a note over 4000', async () => {
    const save = vi.fn(async (_text: string) => ok({}))
    const { wrapper, type, submit } = setup(save)

    await type('abc')
    expect(wrapper.get('[data-testid="note-counter"]').text()).toBe('3 / 4000')

    await type('x'.repeat(4001))
    await submit()

    expect(save).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="note-error"]').text()).toBe('A note has at most 4000 characters.')

    wrapper.unmount()
  })

  it('accepts exactly 1 and exactly 4000 characters', async () => {
    const save = vi.fn(async (_text: string) => ok({}))
    const { wrapper, type, submit } = setup(save)

    await type('x')
    await submit()
    await type('y'.repeat(4000))
    await submit()

    expect(save.mock.calls.map(([text]) => text.length)).toEqual([1, 4000])

    wrapper.unmount()
  })
})

describe('NoteEditor: saving', () => {
  it('sends the text, clears the editor and reports the save', async () => {
    const save = vi.fn(async (_text: string) => ok({}))
    const { wrapper, type, submit } = setup(save)

    await type('check the chat log')
    await submit()

    expect(save).toHaveBeenCalledWith('check the chat log')
    expect((wrapper.get('[data-testid="note-text"]').element as HTMLTextAreaElement).value).toBe('')
    expect(wrapper.emitted('saved')).toHaveLength(1)

    wrapper.unmount()
  })

  it('submits with Ctrl+Enter', async () => {
    const save = vi.fn(async (_text: string) => ok({}))
    const { wrapper, type } = setup(save)

    await type('quick note')
    await wrapper.get('[data-testid="note-text"]').trigger('keydown', { key: 'Enter', ctrlKey: true })
    await flushPromises()

    expect(save).toHaveBeenCalledWith('quick note')

    wrapper.unmount()
  })

  it('does not submit with a plain Enter', async () => {
    const save = vi.fn(async (_text: string) => ok({}))
    const { wrapper, type } = setup(save)

    await type('two\nlines')
    await wrapper.get('[data-testid="note-text"]').trigger('keydown', { key: 'Enter' })
    await flushPromises()

    expect(save).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('keeps the text and shows the server message when it rejects a UUID or IP address', async () => {
    const message = 'a note must not contain a UUID or an IP address; name a player by the short id'
    const save = vi.fn(async () => fail(message, 400))
    const { wrapper, type, submit } = setup(save)

    await type('seen on 10.0.0.1')
    await submit()

    const alert = wrapper.get('[data-testid="note-error"]')
    expect(alert.text()).toBe(message)
    expect(alert.attributes('role')).toBe('alert')
    expect(wrapper.get('[data-testid="note-text"]').attributes('aria-invalid')).toBe('true')
    expect((wrapper.get('[data-testid="note-text"]').element as HTMLTextAreaElement).value).toBe('seen on 10.0.0.1')
    expect(wrapper.emitted('saved')).toBeUndefined()

    wrapper.unmount()
  })

  it('does not send twice while a save is running', async () => {
    let finish: (result: ApiResult<unknown>) => void = () => {}
    const save = vi.fn(() => new Promise<ApiResult<unknown>>((resolve) => (finish = resolve)))
    const { wrapper, type } = setup(save)

    await type('once')
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    await wrapper.get('[data-testid="note-editor"]').trigger('submit')
    finish(ok({}))
    await flushPromises()

    expect(save).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('starts an edit with the current text, keeps it after saving and can be cancelled', async () => {
    const save = vi.fn(async (_text: string) => ok({}))
    const { wrapper, submit } = setup(save, { initial: 'old text', cancellable: true })

    expect((wrapper.get('[data-testid="note-text"]').element as HTMLTextAreaElement).value).toBe('old text')
    await submit()
    expect((wrapper.get('[data-testid="note-text"]').element as HTMLTextAreaElement).value).toBe('old text')

    await wrapper.get('[data-testid="note-cancel"]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)

    wrapper.unmount()
  })
})
