import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { mountWithApp } from '../test/mount'
import { CLIPBOARD_KEY, type ClipboardWriter } from './copyLink'
import CopyLink from './CopyLink.vue'

/** A clipboard that only records writes. It has no read method, so a read would fail the test. */
function fakeClipboard(failWith?: string) {
  const writes: string[] = []
  const writer: ClipboardWriter = {
    writeText: async (text) => {
      if (failWith) throw new Error(failWith)
      writes.push(text)
    },
  }
  return { writes, writer }
}

async function setup(path: string, clipboard = fakeClipboard()) {
  const mounted = await mountWithApp(CopyLink, {
    path,
    provide: { [CLIPBOARD_KEY as symbol]: clipboard.writer },
  })
  return { ...mounted, writes: clipboard.writes }
}

describe('CopyLink', () => {
  it('copies the absolute address of the current view with its query and fragment', async () => {
    const { wrapper, writes } = await setup('/cases/A1B2C3D4?tab=notes-audit#event-7f3c2a10')

    await wrapper.get('[data-testid="copy-link"]').trigger('click')
    await flushPromises()

    expect(writes).toEqual([`${window.location.origin}/cases/A1B2C3D4?tab=notes-audit#event-7f3c2a10`])
    wrapper.unmount()
  })

  it('confirms the copy in text', async () => {
    const { wrapper } = await setup('/reports/0b7c1f2e-8d4a-4c3b-9e2f-1a2b3c4d5e6f')

    await wrapper.get('[data-testid="copy-link"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="copy-link-status"]').text()).toBe('Link copied to the clipboard.')
    wrapper.unmount()
  })

  it('says in text when the clipboard refuses the copy', async () => {
    const { wrapper, writes } = await setup('/players/abc', fakeClipboard('denied'))

    await wrapper.get('[data-testid="copy-link"]').trigger('click')
    await flushPromises()

    expect(writes).toEqual([])
    expect(wrapper.get('[data-testid="copy-link-status"]').text()).toBe('The link could not be copied. Copy it from the address bar.')
    wrapper.unmount()
  })

  it('shows no confirmation before the first copy', async () => {
    const { wrapper } = await setup('/players/abc')

    expect(wrapper.get('[data-testid="copy-link-status"]').text()).toBe('')
    wrapper.unmount()
  })
})
