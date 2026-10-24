import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { leavePath } from '../chat/links'
import { createPortalI18n } from '../i18n'
import ChatLightbox from '../components/ChatLightbox.vue'
import LeaveView from './LeaveView.vue'

/** Sets the fragment the way a visitor's link would: `/leave#<target>`. */
function visit(path: string) {
  window.history.replaceState(null, '', path)
}

function mountLeave(path: string) {
  visit(path)
  return mount(LeaveView, { global: { plugins: [createPortalI18n()] }, attachTo: document.body })
}

afterEach(() => {
  visit('/')
  document.body.innerHTML = ''
})

describe('the interstitial', () => {
  it('shows the full target as text, so the visitor sees where the link goes', () => {
    const wrapper = mountLeave(leavePath('https://example.org/page?x=1'))

    expect(wrapper.get('[data-testid="leave-target"]').text()).toBe('https://example.org/page?x=1')
    expect(wrapper.text()).toContain('Website: example.org')
  })

  it('offers Continue as a new-tab link with no opener and no referrer', () => {
    const wrapper = mountLeave(leavePath('https://example.org/page'))

    const link = wrapper.get('a[target="_blank"]')
    expect(link.attributes('href')).toBe('https://example.org/page')
    expect(link.attributes('rel')).toBe('noopener noreferrer nofollow ugc')
    expect(link.text()).toBe('Continue to the website')
  })

  it('offers Go back as a button, never as a redirect', () => {
    const wrapper = mountLeave(leavePath('https://example.org/'))

    expect(wrapper.get('button').text()).toBe('Go back')
  })

  it('never redirects by itself: the address is the same after it has drawn', async () => {
    const wrapper = mountLeave(leavePath('https://example.org/page'))
    const before = window.location.href

    await flushPromises()

    expect(wrapper.find('a[target="_blank"]').exists()).toBe(true)
    expect(window.location.href).toBe(before)
  })

  it('says a trusted domain is a OneLiteFeather site, and still asks', () => {
    const wrapper = mountLeave(leavePath('https://appeal.onelitefeather.net/case'))

    expect(wrapper.text()).toContain('This is a OneLiteFeather website.')
    expect(wrapper.get('a[target="_blank"]').attributes('href')).toBe('https://appeal.onelitefeather.net/case')
  })

  it.each([
    ['no fragment at all', '/leave'],
    ['an empty fragment', '/leave#'],
    ['a javascript target', `/leave#${btoa('javascript:alert(1)').replace(/=+$/, '')}`],
    ['a data target', `/leave#${btoa('data:text/html,<script>alert(1)</script>').replace(/=+$/, '')}`],
    ['a fragment that is not base64url', '/leave#not valid!!'],
  ])('shows an error and no Continue for %s', (_name, path) => {
    const wrapper = mountLeave(path)

    expect(wrapper.get('[role="alert"]').text()).toContain('This link is not valid.')
    expect(wrapper.find('a[target="_blank"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="leave-target"]').exists()).toBe(false)
  })

  it('offers a way back even when the link is not valid', () => {
    const wrapper = mountLeave('/leave#')

    expect(wrapper.get('button').text()).toBe('Go back')
  })
})

describe('the lightbox', () => {
  function mountBox(src: string | null, name = 'shot.png') {
    return mount(ChatLightbox, { props: { src, name }, global: { plugins: [createPortalI18n()] }, attachTo: document.body })
  }

  it('shows nothing without an image', () => {
    const wrapper = mountBox(null)

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('is a modal dialog named for images, with its image alt text from the file name', () => {
    const wrapper = mountBox('/api/attachments/x1')

    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(dialog.attributes('aria-label')).toBe('Image')
    expect(wrapper.get('img').attributes('alt')).toBe('shot.png')
  })

  it('takes the focus to its close button when it opens', async () => {
    const wrapper = mountBox(null)
    await wrapper.setProps({ src: '/api/attachments/x1' })
    await flushPromises()

    expect(document.activeElement?.textContent?.trim()).toBe('Close')
  })

  it('closes on Escape and on its button', async () => {
    const wrapper = mountBox('/api/attachments/x1')

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await wrapper.get('button').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(2)
  })
})
