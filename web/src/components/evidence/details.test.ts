import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { evidenceItem } from '../../test/evidence'
import { useLookupStore } from '../../stores/lookup'
import AnticheatDetail from './AnticheatDetail.vue'
import ChatLogDetail from './ChatLogDetail.vue'
import FilePreview from './FilePreview.vue'

const ALICE = 'aa'.repeat(64)
const BOB = 'bb'.repeat(64)

describe('AnticheatDetail', () => {
  const item = evidenceItem({
    kind: 'ANTICHEAT',
    payload: { check: 'Speed', violationLevel: 12, server: 'survival-1', values: { speed: 1.4, onGround: false } },
  })

  it('shows the check, the level and the server', async () => {
    const { wrapper } = await mountWithApp(AnticheatDetail, { props: { item } })

    expect(wrapper.text()).toContain('Speed')
    expect(wrapper.text()).toContain('12')
    expect(wrapper.text()).toContain('survival-1')

    wrapper.unmount()
  })

  it('lists the recorded values as a table', async () => {
    const { wrapper } = await mountWithApp(AnticheatDetail, { props: { item } })

    const rows = wrapper.findAll('tbody tr').map((row) => row.text())
    expect(rows).toEqual(['speed1.4', 'onGroundfalse'])

    wrapper.unmount()
  })

  it('shows no table when the record has no values', async () => {
    const { wrapper } = await mountWithApp(AnticheatDetail, { props: { item: evidenceItem({ kind: 'ANTICHEAT', payload: { check: 'Fly' } }) } })

    expect(wrapper.find('table').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('ChatLogDetail', () => {
  const lines = [
    { timestamp: Date.UTC(2026, 6, 1, 12, 0), speaker: ALICE, text: 'hello' },
    { timestamp: '2026-07-01T12:01:00Z', speaker: BOB, text: 'hi there' },
    { timestamp: Date.UTC(2026, 6, 1, 12, 2), speaker: 'Spieler A', text: 'gg' },
    { timestamp: Date.UTC(2026, 6, 1, 12, 3), speaker: null, text: 'server notice' },
    { timestamp: Date.UTC(2026, 6, 1, 12, 4), speaker: ALICE, text: 'again' },
  ]
  const item = evidenceItem({ kind: 'CHAT_LOG', payload: { lines } })

  it('shows every line in order with its text', async () => {
    const { wrapper } = await mountWithApp(ChatLogDetail, { props: { item } })

    expect(wrapper.findAll('[data-testid="chat-line"]').map((line) => line.find('span:last-child').text())).toEqual([
      'hello',
      'hi there',
      'gg',
      'server notice',
      'again',
    ])

    wrapper.unmount()
  })

  it('shows a resolved speaker by name', async () => {
    const { wrapper } = await mountWithApp(ChatLogDetail, { props: { item } })
    useLookupStore().remember(ALICE, 'Alice')
    await wrapper.vm.$nextTick()

    const speakers = wrapper.findAll('[data-testid="speaker"]').map((s) => s.text())
    expect(speakers[0]).toBe('Alice')
    expect(speakers[4]).toBe('Alice')

    wrapper.unmount()
  })

  it('numbers speakers it cannot resolve in order of appearance', async () => {
    const { wrapper } = await mountWithApp(ChatLogDetail, { props: { item } })

    const speakers = wrapper.findAll('[data-testid="speaker"]').map((s) => s.text())
    expect(speakers.slice(0, 2)).toEqual(['Player 1', 'Player 2'])
    expect(speakers[4], 'the same hash keeps its number').toBe('Player 1')

    wrapper.unmount()
  })

  it('keeps an alias the sender chose and names a missing speaker System', async () => {
    const { wrapper } = await mountWithApp(ChatLogDetail, { props: { item } })

    const speakers = wrapper.findAll('[data-testid="speaker"]').map((s) => s.text())
    expect(speakers[2]).toBe('Spieler A')
    expect(speakers[3]).toBe('System')

    wrapper.unmount()
  })

  it('persists no speaker name or hash', async () => {
    window.localStorage.clear()
    const { wrapper } = await mountWithApp(ChatLogDetail, { props: { item } })
    useLookupStore().remember(ALICE, 'Alice')
    await wrapper.vm.$nextTick()

    expect(window.localStorage.length, 'local storage entries').toBe(0)
    expect(window.sessionStorage.length, 'session storage entries').toBe(0)

    wrapper.unmount()
  })
})

describe('FilePreview', () => {
  const file = (contentType: string) => evidenceItem({ kind: 'FILE', file: { name: 'proof', contentType, size: 10, sha256: 'x' } })
  const blob = new Blob(['bytes'], { type: 'image/png' })
  let created: Blob[]
  let revoked: string[]

  // jsdom has no object URLs; each test gets fresh stand-ins that record what the component did.
  beforeEach(() => {
    created = []
    revoked = []
    URL.createObjectURL = (object: Blob | MediaSource) => {
      created.push(object as Blob)
      return `blob:preview-${created.length}`
    }
    URL.revokeObjectURL = (url: string) => {
      revoked.push(url)
    }
  })

  it('renders an image from the fetched content, not from a plain URL', async () => {
    const item = file('image/png')
    const evidenceContent = vi.fn(async () => ok(blob))
    const { wrapper } = await mountWithApp(FilePreview, { api: mockApi({ evidenceContent }), props: { item } })

    const image = wrapper.get('img')
    expect(evidenceContent).toHaveBeenCalledWith(item.identifier)
    expect(created, 'object URLs created').toEqual([blob])
    expect(image.attributes('src')).toBe('blob:preview-1')
    expect(image.attributes('alt')).toBe('proof')

    wrapper.unmount()
  })

  it('renders a video that only preloads its metadata', async () => {
    const { wrapper } = await mountWithApp(FilePreview, { api: mockApi({ evidenceContent: async () => ok(blob) }), props: { item: file('video/mp4') } })

    const video = wrapper.get('video')
    expect(video.attributes('src')).toBe('blob:preview-1')
    expect(video.attributes('preload')).toBe('metadata')
    expect(video.attributes('controls')).toBeDefined()

    wrapper.unmount()
  })

  it('revokes the object URL when it goes away', async () => {
    const { wrapper } = await mountWithApp(FilePreview, { api: mockApi({ evidenceContent: async () => ok(blob) }), props: { item: file('image/png') } })

    wrapper.unmount()

    expect(revoked).toEqual(['blob:preview-1'])
  })

  it('says so when the file cannot be loaded', async () => {
    const { wrapper } = await mountWithApp(FilePreview, { api: mockApi({ evidenceContent: async () => fail('nope', 403) }), props: { item: file('image/png') } })

    expect(wrapper.get('[role="alert"]').text()).toBe('The file could not be loaded.')
    expect(wrapper.find('img').exists(), 'img').toBe(false)

    wrapper.unmount()
  })

  it.each(['application/pdf', 'text/html', 'application/octet-stream', ''])('renders no media and requests nothing for content type "%s"', async (contentType) => {
    const evidenceContent = vi.fn(async () => ok(blob))
    const { wrapper } = await mountWithApp(FilePreview, { api: mockApi({ evidenceContent }), props: { item: file(contentType) } })

    expect(wrapper.find('img').exists(), 'img').toBe(false)
    expect(wrapper.find('video').exists(), 'video').toBe(false)
    expect(wrapper.text()).toContain('No preview for proof.')
    expect(evidenceContent).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})
