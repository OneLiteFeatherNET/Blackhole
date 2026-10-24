import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MessagePosted } from '../api/contract'
import type { PortalApi } from '../api/portal'
import { APPEAL_OPEN, entry, fail, facts, mountPortal, ok, page, POSTED, signedInApi } from '../test/helpers'

let mounted: Awaited<ReturnType<typeof mountPortal>> | undefined

// Only the interval is faked: promises and the DOM keep running normally, and time moves only when the test says so.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
})
afterEach(() => {
  mounted?.wrapper.unmount()
  mounted = undefined
  vi.useRealTimers()
  setHidden(false)
})

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
}

async function openConversation(methods: Partial<PortalApi> = {}, state: Parameters<typeof facts>[0] = { appealState: 'OPEN' }) {
  const conversation = vi.fn(methods.conversation ?? (async () => ok(page([entry(1)]))))
  mounted = await mountPortal(signedInApi({ appeal: async () => ok(APPEAL_OPEN), ...methods, conversation }, facts(state)))
  await mounted.wrapper.findAll('button').find((b) => b.text() === 'Open conversation')!.trigger('click')
  await flushPromises()
  return { conversation, wrapper: mounted.wrapper, session: mounted.session }
}

async function tick(ms: number) {
  vi.advanceTimersByTime(ms)
  await flushPromises()
}

describe('thread', () => {
  it('lists messages as an ordered list with times, in order', async () => {
    const { wrapper } = await openConversation({ conversation: async () => ok(page([entry(2, { author: 'PLAYER', text: 'second' }), entry(1, { text: 'first' })])) })

    const items = wrapper.findAll('ol > li')
    expect(items.map((li) => li.get('p:nth-of-type(2)').text())).toEqual(['first', 'second'])
    expect(items[0]!.find('time').attributes('datetime')).toBe('2026-10-02T10:00:00Z')
    expect(items[0]!.text()).toContain('Moderation team')
    expect(items[1]!.text()).toContain('You')
  })

  it('announces new messages politely', async () => {
    const { wrapper } = await openConversation()

    const log = wrapper.get('[role="log"]')
    expect(log.attributes('aria-live')).toBe('polite')
    expect(log.attributes('aria-label')).toBe('Messages')
  })

  it('shows a removed message without its text', async () => {
    const { wrapper } = await openConversation({ conversation: async () => ok(page([entry(1, { removed: true, text: 'should not show' })])) })

    expect(wrapper.get('ol').text()).toContain('Message removed')
    expect(wrapper.get('ol').text()).not.toContain('should not show')
  })

  it('shows markers as plain notes', async () => {
    const { wrapper } = await openConversation({ conversation: async () => ok(page([entry(1, { kind: 'APPEAL_FILED', author: 'SYSTEM', text: null }), entry(2, { kind: 'SOMETHING_NEW', author: 'SYSTEM', text: null })])) })

    expect(wrapper.get('ol').text()).toContain('Appeal filed')
    expect(wrapper.get('ol').text()).toContain('Update')
  })

  it('replaces a message the backend sends again with a new text', async () => {
    const replies = [page([entry(1, { text: 'before' })]), page([entry(2, { messageId: 'm-1', text: 'after' })])]
    const { wrapper, conversation } = await openConversation({ conversation: async () => ok(replies.shift()!) })

    await tick(15_000)

    expect(conversation).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('ol > li')).toHaveLength(1)
    expect(wrapper.get('ol').text()).toContain('after')
  })
})

describe('plain text', () => {
  const MARKUP = '<b>bold</b> <img src=x onerror=alert(1)> [link](https://evil.example) **md**\nsecond line'

  it('renders message text literally: no elements, no links', async () => {
    const { wrapper } = await openConversation({ conversation: async () => ok(page([entry(1, { text: MARKUP })])) })

    const item = wrapper.get('ol > li')
    expect(item.text()).toContain('<b>bold</b>')
    expect(item.text()).toContain('[link](https://evil.example)')
    expect(item.find('b').exists()).toBe(false)
    expect(item.find('img').exists()).toBe(false)
    expect(item.find('a').exists()).toBe(false)
    expect(item.html()).toContain('&lt;b&gt;bold&lt;/b&gt;')
  })

  it('keeps the line breaks of the text', async () => {
    const { wrapper } = await openConversation({ conversation: async () => ok(page([entry(1, { text: MARKUP })])) })

    expect(wrapper.get('ol > li p.plain-text').text()).toContain('second line')
  })

  it('renders the facts as plain text too', async () => {
    mounted = await mountPortal(signedInApi({}, facts({ reason: '<i>why</i>' })))

    expect(mounted.wrapper.get('dl').text()).toContain('<i>why</i>')
    expect(mounted.wrapper.find('dl i').exists()).toBe(false)
  })
})

describe('polling', () => {
  it('asks again every 15 seconds while the tab is visible, not earlier', async () => {
    const { conversation } = await openConversation()
    expect(conversation).toHaveBeenCalledTimes(1)

    await tick(14_999)
    expect(conversation).toHaveBeenCalledTimes(1)
    await tick(1)
    expect(conversation).toHaveBeenCalledTimes(2)
    await tick(15_000)
    expect(conversation).toHaveBeenCalledTimes(3)
  })

  it('passes the last seq as the cursor', async () => {
    const { conversation } = await openConversation({ conversation: async () => ok(page([entry(7)])) })

    await tick(15_000)

    expect(conversation).toHaveBeenNthCalledWith(1, null)
    expect(conversation).toHaveBeenNthCalledWith(2, 7)
  })

  it('does not poll while the tab is hidden, and polls at once when it is shown again', async () => {
    const { conversation } = await openConversation()

    setHidden(true)
    document.dispatchEvent(new Event('visibilitychange'))
    await tick(60_000)
    expect(conversation, 'nothing while hidden').toHaveBeenCalledTimes(1)

    setHidden(false)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(conversation, 'immediately on return').toHaveBeenCalledTimes(2)
    await tick(15_000)
    expect(conversation).toHaveBeenCalledTimes(3)
  })

  it('stops polling when the page is left', async () => {
    const { conversation, wrapper } = await openConversation()

    await wrapper.findAll('button').find((b) => b.text() === 'Back')!.trigger('click')
    await tick(60_000)

    expect(conversation).toHaveBeenCalledTimes(1)
  })

  it('does not start a second request while one is still running', async () => {
    let release: () => void = () => undefined
    const calls = vi.fn()
    const { conversation } = await openConversation({
      conversation: (cursor) => {
        calls(cursor)
        return calls.mock.calls.length === 1 ? Promise.resolve(ok(page([entry(1)]))) : new Promise((resolve) => (release = () => resolve(ok(page([entry(2)])))))
      },
    })

    await tick(15_000)
    await tick(15_000)
    expect(conversation).toHaveBeenCalledTimes(2)
    release()
    await flushPromises()
  })

  it('shows the expiry page when a poll finds the session ended', async () => {
    const replies = [ok(page([entry(1)])), fail<never>('SESSION_EXPIRED', 401)]
    const { wrapper } = await openConversation({ conversation: async () => replies.shift()! })

    await tick(15_000)

    expect(wrapper.get('h1').text()).toBe('Your session has ended')
  })

  it('keeps the thread and tries again after a failed poll', async () => {
    const replies = [ok(page([entry(1)])), fail<never>('UNAVAILABLE', 503), ok(page([entry(2)]))]
    const { wrapper } = await openConversation({ conversation: async () => replies.shift()! })

    await tick(15_000)
    expect(wrapper.get('[role="alert"]').text()).toContain('could not be loaded')
    expect(wrapper.get('ol').text()).toContain('message 1')
    await tick(15_000)

    expect(wrapper.get('ol').text()).toContain('message 2')
    expect(wrapper.text()).not.toContain('could not be loaded')
  })
})

describe('composer', () => {
  it('is labelled, counts characters and sends with the button', async () => {
    const postMessage = vi.fn(async () => ok(POSTED))
    const { wrapper } = await openConversation({ postMessage })

    expect(wrapper.get('label[for="message"]').text()).toBe('Your message')
    await wrapper.get('textarea#message').setValue('hello team')
    expect(wrapper.get('#message-count').text()).toBe('10 of 2000 characters')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(postMessage).toHaveBeenCalledWith('hello team', expect.stringMatching(/^[0-9a-f-]{36}$/))
  })

  it('sends with Ctrl+Enter from the keyboard', async () => {
    const postMessage = vi.fn(async () => ok(POSTED))
    const { wrapper } = await openConversation({ postMessage })

    await wrapper.get('textarea#message').setValue('keyboard only')
    await wrapper.get('textarea#message').trigger('keydown', { key: 'Enter', ctrlKey: true })
    await flushPromises()

    expect(postMessage).toHaveBeenCalledOnce()
  })

  it('does not send on a plain Enter, which is a new line', async () => {
    const postMessage = vi.fn(async () => ok(POSTED))
    const { wrapper } = await openConversation({ postMessage })

    await wrapper.get('textarea#message').setValue('line')
    await wrapper.get('textarea#message').trigger('keydown', { key: 'Enter' })

    expect(postMessage).not.toHaveBeenCalled()
  })

  it('does not send an empty or too long message', async () => {
    const postMessage = vi.fn(async () => ok(POSTED))
    const { wrapper } = await openConversation({ postMessage })

    await wrapper.get('textarea#message').setValue('   ')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('textarea#message').setValue('x'.repeat(2001))
    await wrapper.get('form').trigger('submit')

    expect(postMessage).not.toHaveBeenCalled()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('textarea#message').attributes('aria-invalid')).toBe('true')
  })

  it('clears the draft and reads the new message after sending', async () => {
    const replies = [page([entry(1)]), page([entry(2, { author: 'PLAYER', text: 'hello team' })])]
    const { wrapper, session } = await openConversation({ conversation: async () => ok(replies.shift()!), postMessage: async () => ok(POSTED) })

    await wrapper.get('textarea#message').setValue('hello team')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(session.state.drafts.message).toBe('')
    expect(wrapper.get('ol').text()).toContain('hello team')
  })

  it('keeps the draft in memory only when sending fails, and reuses the idempotency key for the same text', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const postMessage = vi.fn(async () => fail<MessagePosted>('UNAVAILABLE', 503))
    const { wrapper, session } = await openConversation({ postMessage })

    await wrapper.get('textarea#message').setValue('important')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('textarea#message').setValue('changed')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const keys = postMessage.mock.calls.map((call) => (call as unknown[])[1])
    expect(keys[0]).toBe(keys[1])
    expect(keys[2]).not.toBe(keys[0])
    expect(session.state.drafts.message).toBe('changed')
    expect(wrapper.get('[role="alert"]').text()).toContain('could not be sent')
    expect(setItem).not.toHaveBeenCalled()
  })

  it.each([
    ['PII_REJECTED', 'remove IP addresses'],
    ['VALIDATION', 'between 1 and 2000'],
    ['FROZEN', 'frozen'],
  ] as const)('explains %s in the alert region', async (code, text) => {
    const { wrapper } = await openConversation({ postMessage: async () => fail<MessagePosted>(code, 409) })

    await wrapper.get('textarea#message').setValue('x')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain(text)
  })

  it('shows the expiry page and keeps the unsent text when sending finds the session ended', async () => {
    const { wrapper, session } = await openConversation({ postMessage: async () => fail<MessagePosted>('SESSION_EXPIRED', 401) })

    await wrapper.get('textarea#message').setValue('long thoughts')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Your session has ended')
    expect(session.state.drafts.message).toBe('long thoughts')
  })
})

describe('states', () => {
  it('has no composer for a frozen conversation and says why', async () => {
    const { wrapper } = await openConversation({}, { appealState: 'FROZEN' })

    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.text()).toContain('Your appeal is frozen')
  })

  it('is read-only once the appeal is decided, with the decision', async () => {
    const { wrapper } = await openConversation(
      { appeal: async () => ok({ ...APPEAL_OPEN, status: 'DENIED', decision: 'DENIED' }) },
      { appealState: 'DECIDED' },
    )

    expect(wrapper.find('textarea').exists(), 'the backend answers NO_APPEAL after a decision').toBe(false)
    expect(wrapper.text()).toContain('Your appeal was denied.')
    const links = wrapper.findAll('a[download]').map((a) => a.attributes('href'))
    expect(links).toEqual(['/api/appeal/protocol?format=text', '/api/appeal/protocol?format=json'])
  })

  it('shows the new expiry of a grant', async () => {
    const { wrapper } = await openConversation(
      { appeal: async () => ok({ ...APPEAL_OPEN, decision: 'GRANTED_REDUCTION', newExpiresAt: '2026-11-20T00:00:00Z' }) },
      { appealState: 'DECIDED' },
    )

    expect(wrapper.text()).toContain('shortened')
    expect(wrapper.text()).toMatch(/now runs until .*2026/)
  })
})

describe('protocol copy', () => {
  it('is not offered before a decision', async () => {
    const { wrapper } = await openConversation()

    expect(wrapper.find('a[download]').exists()).toBe(false)
  })

  it('is not offered to a limited session', async () => {
    const { wrapper } = await openConversation({ conversation: async () => fail('FORBIDDEN', 403), appeal: async () => ok({ ...APPEAL_OPEN, decision: 'DENIED' }) }, { appealState: 'DECIDED' })

    expect(wrapper.find('a[download]').exists()).toBe(false)
  })
})

describe('limited session', () => {
  const limited = { session: async () => ok({ csrf: 'c', expiresAt: '2099-01-01T00:00:00Z', scope: 'LIMITED' as const }) }

  it('never asks for the conversation or the appeal', async () => {
    const conversation = vi.fn()
    const appeal = vi.fn()
    mounted = await mountPortal(signedInApi({ ...limited, conversation, appeal }, facts({ appealState: 'OPEN' })))
    await flushPromises()

    expect(conversation).not.toHaveBeenCalled()
    expect(appeal).not.toHaveBeenCalled()
  })

  it('shows the notice instead of an error when the backend refuses with 403', async () => {
    const { wrapper } = await openConversation({ conversation: async () => fail('FORBIDDEN', 403) })

    expect(wrapper.text()).toContain('shared publicly')
    expect(wrapper.find('[role="alert"]').exists(), 'not an error').toBe(false)
    expect(wrapper.find('textarea').exists()).toBe(false)
  })

  it('does not keep polling a refused conversation into an error', async () => {
    const { wrapper } = await openConversation({ conversation: async () => fail('FORBIDDEN', 403) })

    await tick(30_000)

    expect(wrapper.text()).not.toContain('could not be loaded')
  })
})

describe('paging', () => {
  it('reads the next page at once when a page is full, using the last seq as the cursor', async () => {
    const full = Array.from({ length: 200 }, (_, i) => entry(i + 1))
    const replies = [page(full), page([entry(201)])]
    const { conversation, wrapper } = await openConversation({ conversation: async () => ok(replies.shift()!) })

    expect(conversation).toHaveBeenCalledTimes(2)
    expect(conversation).toHaveBeenNthCalledWith(2, 200)
    expect(wrapper.findAll('ol > li')).toHaveLength(201)
  })

  it('keeps the cursor when the conversation is still empty', async () => {
    const { conversation } = await openConversation({ conversation: async () => ok({ entries: [], cursor: null }) })

    await tick(15_000)

    expect(conversation).toHaveBeenNthCalledWith(2, null)
  })
})

describe('posting after the state changed', () => {
  it('hides the composer when the backend says there is no open appeal any more', async () => {
    const replies = [facts({ appealState: 'OPEN' }), facts({ appealState: 'DECIDED' })]
    const { wrapper } = await openConversation(
      { postMessage: async () => fail<MessagePosted>('NO_APPEAL', 409), punishment: async () => ok(replies.length > 1 ? replies.shift()! : replies[0]!) },
    )

    await wrapper.get('textarea#message').setValue('late')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('textarea').exists()).toBe(false)
  })
})

describe('keyboard', () => {
  it('reaches every control by tabbing: only buttons, fields and links are focusable', async () => {
    const { wrapper } = await openConversation({ appeal: async () => ok({ ...APPEAL_OPEN, decision: 'DENIED' }) }, { appealState: 'DECIDED' })

    const positive = wrapper.findAll('[tabindex]').filter((el) => Number(el.attributes('tabindex')) > 0)
    expect(positive, 'no positive tabindex').toHaveLength(0)
    for (const control of wrapper.findAll('button, a[href]')) {
      expect(control.attributes('tabindex')).toBeUndefined()
    }
  })
})
