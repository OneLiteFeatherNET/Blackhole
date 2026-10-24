import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { ChatAttachment, ChatShare, ConversationEntry, MarkdownNode } from '../api/contract'
import { createPortalI18n } from '../i18n'
import MessageBubble from './MessageBubble.vue'
import { entry } from '../test/helpers'

const IMAGE_ID = '3f2b8c1e-aaaa-4bbb-8ccc-0123456789ab'
const PDF_ID = '4a2b8c1e-aaaa-4bbb-8ccc-0123456789ab'

function bubble(overrides: Partial<ConversationEntry> = {}, props: { own?: boolean; grouped?: boolean; authorLabel?: string } = {}) {
  return mount(MessageBubble, {
    props: { entry: entry(1, overrides), authorLabel: props.authorLabel ?? 'Moderation team', own: props.own ?? false, grouped: props.grouped ?? false },
    global: { plugins: [createPortalI18n()] },
  })
}

const image: ChatAttachment = { attachmentId: IMAGE_ID, name: 'shot.png', contentType: 'image/png', size: 2048, provenance: 'PLAYER' }
const pdf: ChatAttachment = { attachmentId: PDF_ID, name: 'appeal.pdf', contentType: 'application/pdf', size: 3 * 1024 * 1024, provenance: 'PLAYER' }

describe('sides and author', () => {
  it('puts the player on the own side and the team on the other', () => {
    const own = bubble({ author: 'PLAYER' }, { own: true })
    const other = bubble({ author: 'STAFF' }, { own: false })

    expect(own.classes()).toContain('bg-secondary-container')
    expect(other.classes()).toContain('bg-surface-container-high')
  })

  it('names the author above the first message of a run only', () => {
    expect(bubble({}, { grouped: false, authorLabel: 'Moderation team' }).text()).toContain('Moderation team')
    expect(bubble({}, { grouped: true, authorLabel: 'Moderation team' }).text()).not.toContain('Moderation team')
  })

  it('shows the time as a machine-readable datetime', () => {
    const wrapper = bubble({ occurredAt: '2026-10-02T10:00:00Z' })

    expect(wrapper.get('time').attributes('datetime')).toBe('2026-10-02T10:00:00Z')
  })
})

describe('body', () => {
  it('draws the Markdown tree when the entry has blocks', () => {
    const blocks: MarkdownNode[] = [{ type: 'paragraph', children: [{ type: 'strong', children: [{ type: 'text', text: 'bold' }] }] }]
    const wrapper = bubble({ text: '**bold**', blocks })

    expect(wrapper.get('strong').text()).toBe('bold')
  })

  it('shows the stored text as plain text when there are no blocks', () => {
    const wrapper = bubble({ text: '<b>bold</b>', blocks: null })

    expect(wrapper.find('b').exists()).toBe(false)
    expect(wrapper.text()).toContain('<b>bold</b>')
  })

  it('shows a removed message without its text or files', () => {
    const wrapper = bubble({ removed: true, text: 'secret', blocks: null, attachments: [image] })

    expect(wrapper.text()).toContain('Message removed')
    expect(wrapper.text()).not.toContain('secret')
    expect(wrapper.find('img').exists()).toBe(false)
  })
})

describe('files', () => {
  it('shows an image as a button that opens it, and emits the same-origin URL', async () => {
    const wrapper = bubble({ attachments: [image] })

    const button = wrapper.get('button[aria-label="Open shot.png"]')
    expect(wrapper.get('img').attributes('src')).toBe(`/api/attachments/${IMAGE_ID}`)
    expect(wrapper.get('img').attributes('alt')).toBe('shot.png')
    await button.trigger('click')

    expect(wrapper.emitted('image')).toEqual([[`/api/attachments/${IMAGE_ID}`, 'shot.png']])
  })

  it('shows a PDF as a file card with a size and a download link', () => {
    const wrapper = bubble({ attachments: [pdf] })

    const link = wrapper.get('a[download]')
    expect(link.attributes('href')).toBe(`/api/attachments/${PDF_ID}`)
    expect(link.text()).toBe('Download appeal.pdf')
    expect(wrapper.text()).toContain('PDF document · 3.0 MB')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('leaves out a file whose id is not a plain token', () => {
    const wrapper = bubble({ attachments: [{ ...image, attachmentId: '../../session' }] })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('shows a hostile file name as text and never as markup', () => {
    const hostile = { ...pdf, name: '<img src=x onerror=alert(1)>.pdf' }
    const wrapper = bubble({ attachments: [hostile] })

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>.pdf')
    expect(wrapper.find('img').exists()).toBe(false)
  })
})

describe('share cards', () => {
  function shared(share: ChatShare) {
    return bubble({ kind: 'SHARE', author: 'STAFF', share, text: null, blocks: null })
  }

  it('shows an excerpt with the speakers as the backend relabelled them', () => {
    const wrapper = shared({
      shareId: 's1',
      mode: 'EXCERPT',
      state: 'ACTIVE',
      lineCount: 2,
      lines: [
        { speaker: 'You', offsetSeconds: 0, text: 'hello' },
        { speaker: 'Player 2', offsetSeconds: 4, text: 'hi' },
      ],
    })

    expect(wrapper.get('figcaption').text()).toBe('Shared by the moderation team')
    expect(wrapper.text()).toContain('Player 2:')
    expect(wrapper.text()).toContain('hi')
  })

  it('shows a shared image as a button that opens it', () => {
    const wrapper = shared({ shareId: 'share-1', mode: 'FILE', state: 'ACTIVE', lineCount: null, lines: null })

    expect(wrapper.get('img').attributes('src')).toBe('/api/attachments/share-1')
  })

  it('says a withdrawn share is no longer shared and shows no content', () => {
    const wrapper = shared({ shareId: 's1', mode: 'EXCERPT', state: 'WITHDRAWN', lineCount: null, lines: null })

    expect(wrapper.get('figcaption').text()).toBe('No longer shared')
    expect(wrapper.find('blockquote').exists()).toBe(false)
  })

  it('says an unavailable share is no longer available', () => {
    const wrapper = shared({ shareId: 's1', mode: null, state: 'UNAVAILABLE', lineCount: null, lines: null })

    expect(wrapper.get('figcaption').text()).toBe('This evidence is no longer available')
  })
})
