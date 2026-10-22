import { afterEach, describe, expect, it, vi } from 'vitest'
import { mountWithApp } from '../../test/mount'
import SafeLink from './SafeLink.vue'
import { safeHttpUrl } from './safeLink'

describe('SafeLink', () => {
  afterEach(() => vi.restoreAllMocks())

  it('opens an https link in a new tab without opener or referrer', async () => {
    const { wrapper } = await mountWithApp(SafeLink, { props: { href: 'https://replay.example/r/42?t=1' } })

    const anchor = wrapper.get('a')
    expect(anchor.attributes('href')).toBe('https://replay.example/r/42?t=1')
    expect(anchor.attributes('target')).toBe('_blank')
    expect(anchor.attributes('rel')).toBe('noopener noreferrer')
    expect(anchor.text()).toContain('replay.example')

    wrapper.unmount()
  })

  it('renders an http link as an anchor too', async () => {
    const { wrapper } = await mountWithApp(SafeLink, { props: { href: 'http://example.org/x' } })

    expect(wrapper.find('a').exists()).toBe(true)

    wrapper.unmount()
  })

  it.each(['javascript:alert(1)', 'data:text/html,<script>alert(1)</script>', 'ftp://example.org/x', '/relative/path', 'not a url'])(
    'shows %s as plain text without an anchor',
    async (value) => {
      const { wrapper } = await mountWithApp(SafeLink, { props: { href: value } })

      expect(wrapper.find('a').exists(), 'anchor').toBe(false)
      expect(wrapper.get('[data-testid="plain-link"]').text()).toBe(value)

      wrapper.unmount()
    },
  )

  it('never requests the link target', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const { wrapper } = await mountWithApp(SafeLink, { props: { href: 'https://replay.example/r/42' } })

    expect(fetchSpy).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('safeHttpUrl', () => {
  it('rejects an empty value', () => {
    expect(safeHttpUrl('')).toBeNull()
    expect(safeHttpUrl(null)).toBeNull()
  })

  it('trims surrounding blanks before parsing', () => {
    expect(safeHttpUrl('  https://example.org/a ')?.href).toBe('https://example.org/a')
  })
})
