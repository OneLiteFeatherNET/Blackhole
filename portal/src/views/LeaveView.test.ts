import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
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
  /** jsdom has no modal dialog: these stand in for the browser's showModal and close, which set `open` and fire `close`. */
  function installModalDialog() {
    const showModal = vi.fn(function (this: HTMLDialogElement) {
      this.open = true
    })
    const close = vi.fn(function (this: HTMLDialogElement) {
      this.open = false
      this.dispatchEvent(new Event('close'))
    })
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, writable: true, value: showModal })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, writable: true, value: close })
    return { showModal, close }
  }

  const patched = ['showModal', 'close'] as const
  const originals = patched.map((name) => [name, Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name)] as const)
  afterEach(() => {
    for (const [name, descriptor] of originals) {
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
      else delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>)[name]
    }
  })

  function mountBox(src: string | null, name = 'shot.png') {
    return mount(ChatLightbox, { props: { src, name }, global: { plugins: [createPortalI18n()] }, attachTo: document.body })
  }

  it('shows no image and stays closed without a picture', () => {
    installModalDialog()
    const wrapper = mountBox(null)

    expect(wrapper.find('img').exists()).toBe(false)
    expect((wrapper.get('dialog').element as HTMLDialogElement).open).toBe(false)
  })

  it('opens as a modal dialog, so the page behind it is inert', () => {
    const { showModal } = installModalDialog()
    const wrapper = mountBox('/api/attachments/x1')

    expect(showModal).toHaveBeenCalledOnce()
    expect(wrapper.get('dialog').attributes('aria-label')).toBe('Image')
    expect(wrapper.get('img').attributes('alt')).toBe('shot.png')
  })

  it('takes the focus to its close button when it opens', async () => {
    installModalDialog()
    const wrapper = mountBox(null)
    await wrapper.setProps({ src: '/api/attachments/x1' })
    await flushPromises()

    expect(document.activeElement?.textContent?.trim()).toBe('Close')
  })

  it('gives the focus back to what opened it when it closes', async () => {
    installModalDialog()
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const wrapper = mountBox(null)
    await wrapper.setProps({ src: '/api/attachments/x1' })
    await flushPromises()
    expect(document.activeElement, 'focus moved into the dialog').not.toBe(opener)

    await wrapper.setProps({ src: null })
    await flushPromises()

    expect(document.activeElement).toBe(opener)
    opener.remove()
  })

  it('tells the page when the dialog is closed with Escape', () => {
    installModalDialog()
    const wrapper = mountBox('/api/attachments/x1')

    // The browser closes a dialog on Escape and fires its close event; the page learns of it from that event.
    wrapper.get('dialog').element.dispatchEvent(new Event('close'))

    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('closes with its button and tells the page once', async () => {
    const { close } = installModalDialog()
    const wrapper = mountBox('/api/attachments/x1')

    await wrapper.get('button').trigger('click')

    expect(close).toHaveBeenCalledOnce()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
