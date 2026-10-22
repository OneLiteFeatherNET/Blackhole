import { describe, expect, it } from 'vitest'
import { mockApi, mountWithApp } from '../../test/mount'
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
  const api = mockApi({ evidenceContentUrl: (id: string) => `/api/evidence/${id}/content` })

  it('renders an image from the backend content endpoint', async () => {
    const item = file('image/png')
    const { wrapper } = await mountWithApp(FilePreview, { api, props: { item } })

    const image = wrapper.get('img')
    expect(image.attributes('src')).toBe(`/api/evidence/${item.identifier}/content`)
    expect(image.attributes('alt')).toBe('proof')

    wrapper.unmount()
  })

  it('renders a video that only preloads its metadata', async () => {
    const item = file('video/mp4')
    const { wrapper } = await mountWithApp(FilePreview, { api, props: { item } })

    const video = wrapper.get('video')
    expect(video.attributes('src')).toBe(`/api/evidence/${item.identifier}/content`)
    expect(video.attributes('preload')).toBe('metadata')
    expect(video.attributes('controls')).toBeDefined()

    wrapper.unmount()
  })

  it.each(['application/pdf', 'text/html', 'application/octet-stream', ''])('renders no media for content type "%s"', async (contentType) => {
    const { wrapper } = await mountWithApp(FilePreview, { api, props: { item: file(contentType) } })

    expect(wrapper.find('img').exists(), 'img').toBe(false)
    expect(wrapper.find('video').exists(), 'video').toBe(false)
    expect(wrapper.text()).toContain('No preview for proof.')

    wrapper.unmount()
  })
})
