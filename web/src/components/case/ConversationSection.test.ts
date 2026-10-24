import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CaseTimelineItem } from '../../api/types'
import { caseResponse, ME, OTHER, timelineItem } from '../../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import ConversationSection from './ConversationSection.vue'

const PLAYER_MSG = '3f2b8c1e-0000-4000-8000-000000000021'
const MINE = '3f2b8c1e-0000-4000-8000-000000000022'
const THEIRS = '3f2b8c1e-0000-4000-8000-000000000023'

function message(seq: number, id: string, text: string | null, who: 'PLAYER' | 'ME' | 'OTHER', extra: Partial<CaseTimelineItem> = {}) {
  const actor =
    who === 'PLAYER' ? { trust: 'PLAYER' as const } : { oid: who === 'ME' ? ME : OTHER, name: who === 'ME' ? 'Me Myself' : 'Ada', trust: 'VERIFIED' as const }
  return timelineItem({
    seq,
    type: 'message.added',
    subjectId: id,
    visibility: 'PLAYER',
    actor,
    payload: { messageId: id, author: { kind: who === 'PLAYER' ? 'PLAYER' : 'STAFF' }, channel: who === 'PLAYER' ? 'PORTAL' : 'PANEL', ...(text === null ? {} : { text }) },
    ...extra,
  })
}

const THREAD = [message(1, PLAYER_MSG, 'Please look again', 'PLAYER'), message(2, MINE, 'We will', 'ME'), message(3, THEIRS, 'Colleague reply', 'OTHER')]

function setup(options: { roles?: string[]; events?: CaseTimelineItem[]; kase?: Parameters<typeof caseResponse>[0]; extra?: Record<string, unknown> } = {}) {
  const api = {
    caseConversation: vi.fn(async () => ok(options.events ?? THREAD)),
    postCaseMessage: vi.fn(async () => ok({ messageId: 'new', seq: 9, repeated: false })),
    editCaseMessage: vi.fn(async () => ok({ messageId: MINE, seq: 10, repeated: false })),
    deleteCaseMessage: vi.fn(async () => ok({ messageId: MINE, seq: 11, repeated: false })),
    redactCaseMessage: vi.fn(async () => ok({ messageId: THEIRS, redactedVersions: 1 })),
    eraseCasePlayerMessages: vi.fn(async () => ok({ redactedMessages: 1 })),
    ...options.extra,
  }
  return {
    api,
    mount: () => mountWithApp(ConversationSection, { api: mockApi(api), roles: options.roles, props: { kase: caseResponse(options.kase) } }),
  }
}

describe('ConversationSection: thread', () => {
  it('shows player and staff messages in order, each labelled by its author', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const items = wrapper.findAll('[data-testid="thread"] > li')
    expect(items.map((item) => item.attributes('data-author'))).toEqual(['PLAYER', 'STAFF', 'STAFF'])
    expect(items.map((item) => item.get('[data-testid="message-author"]').text())).toEqual(['Player', 'Me Myself', 'Ada'])
    expect(items[0]!.get('[data-testid="from-player"]').text()).toContain('not verified')
    expect(items[1]!.find('[data-testid="from-player"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows an edited, a deleted and a redacted message in their states', async () => {
    const events = [
      message(1, MINE, 'v1', 'ME'),
      timelineItem({ seq: 2, type: 'message.edited', subjectId: MINE, payload: { messageId: MINE, text: 'v2' } }),
      message(3, THEIRS, 'withdrawn', 'OTHER'),
      timelineItem({ seq: 4, type: 'message.deleted', subjectId: THEIRS, payload: { messageId: THEIRS } }),
      message(5, PLAYER_MSG, 'phone 555', 'PLAYER'),
      timelineItem({ seq: 6, type: 'message.redacted', subjectId: PLAYER_MSG, payload: { messageId: PLAYER_MSG, reason: 'PERSONAL_DATA' } }),
    ]
    const { mount } = setup({ events })
    const { wrapper } = await mount()

    const mine = wrapper.get(`[data-testid="message-${MINE}"]`)
    expect(mine.get('[data-testid="message-text-view"]').text()).toBe('v2')
    expect(mine.get('[data-testid="edited"]').text()).toBe('edited')

    const deleted = wrapper.get(`[data-testid="message-${THEIRS}"]`)
    expect(deleted.get('[data-testid="deleted"]').text()).toBe('deleted')
    expect(deleted.get('[data-testid="message-text-view"]').text(), 'staff still see the last text').toBe('withdrawn')

    const redacted = wrapper.get(`[data-testid="message-${PLAYER_MSG}"]`)
    expect(redacted.get('[data-testid="message-removed"]').text()).toBe('Removed (data protection)')
    expect(redacted.text()).not.toContain('phone 555')

    wrapper.unmount()
  })

  it('renders message text as plain text, never as markup', async () => {
    const hostile = '<img src=x onerror="window.__pwned = true"><b>bold</b> <script>window.__pwned = true</script>'
    const { mount } = setup({ events: [message(1, PLAYER_MSG, hostile, 'PLAYER')] })
    const { wrapper } = await mount()

    const view = wrapper.get('[data-testid="message-text-view"]')
    expect(view.text()).toBe(hostile)
    expect(view.find('img, b, script').exists(), 'no element was created from the text').toBe(false)
    expect((window as unknown as { __pwned?: boolean }).__pwned).toBeUndefined()

    wrapper.unmount()
  })

  it('marks a staff-only message', async () => {
    const { mount } = setup({ events: [message(1, THEIRS, 'internal noise', 'OTHER', { visibility: 'STAFF' })] })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="staff-only"]').text()).toBe('Staff only')

    wrapper.unmount()
  })

  it('says so when there are no messages and keeps a polite live region for changes', async () => {
    const { mount } = setup({ events: [] })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="conversation-empty"]').text()).toBe('No messages yet.')
    const live = wrapper.get('[data-testid="conversation-status"]')
    expect(live.attributes('role')).toBe('status')
    expect(live.attributes('aria-live')).toBe('polite')

    wrapper.unmount()
  })

  it('shows the error with a retry when the conversation cannot be loaded', async () => {
    const caseConversation = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok(THREAD))
    const { mount } = setup({ extra: { caseConversation } })
    const { wrapper } = await mount()

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('[data-testid="thread"] > li')).toHaveLength(3)

    wrapper.unmount()
  })
})

describe('ConversationSection: banners', () => {
  const leak = (afterFiling: boolean) =>
    timelineItem({ seq: 10, type: 'ban_id.leak_marked', payload: { reason: 'POSTED_PUBLIC', source: 'STAFF', afterFiling } })

  it('explains a forfeited right', async () => {
    const { mount } = setup({ events: [...THREAD, leak(false)], kase: { banIdLeakedAt: 1700, banIdLeakReason: 'POSTED_PUBLIC' } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="banner-forfeited"]').attributes('role')).toBe('status')
    expect(wrapper.get('[data-testid="banner-forfeited"]').text()).toContain('forfeited')
    expect(wrapper.find('[data-testid="banner-frozen"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('explains a frozen ticket and keeps the composer for staff', async () => {
    const { mount } = setup({ events: [...THREAD, leak(true)], kase: { banIdLeakedAt: 1700, banIdLeakReason: 'SHARED' } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="banner-frozen"]').text()).toContain('frozen')
    expect(wrapper.find('[data-testid="message-composer"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows no banner for a Ban ID that is not leaked', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid^="banner-"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('ConversationSection: composing', () => {
  it('sends a reply with an idempotency key, clears the composer, reloads and announces it', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    const changed = () => (wrapper.emitted('changed') ?? []).map((args) => args[0])

    await wrapper.get('[data-testid="message-text"]').setValue('Thanks, we checked.')
    await wrapper.get('[data-testid="message-composer"]').trigger('submit')
    await flushPromises()

    expect(api.postCaseMessage).toHaveBeenCalledWith('p-0001', 'Thanks, we checked.', expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect((wrapper.get('[data-testid="message-text"]').element as HTMLTextAreaElement).value).toBe('')
    expect(api.caseConversation, 'the thread is reloaded').toHaveBeenCalledTimes(2)
    expect(changed()).toEqual(['Reply sent.'])
    expect(wrapper.get('[data-testid="conversation-status"]').text()).toBe('Reply sent.')

    wrapper.unmount()
  })

  it('counts to 2000 and refuses a longer message without calling the server', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="message-text"]').setValue('abc')
    expect(wrapper.get('[data-testid="message-counter"]').text()).toBe('3 / 2000')
    await wrapper.get('[data-testid="message-text"]').setValue('x'.repeat(2001))
    await wrapper.get('[data-testid="message-composer"]').trigger('submit')
    await flushPromises()

    expect(api.postCaseMessage).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="message-error"]').text()).toBe('A message has at most 2000 characters.')

    wrapper.unmount()
  })

  it('sends with Ctrl+Enter', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="message-text"]').setValue('quick')
    await wrapper.get('[data-testid="message-text"]').trigger('keydown', { key: 'Enter', ctrlKey: true })
    await flushPromises()

    expect(api.postCaseMessage).toHaveBeenCalledOnce()

    wrapper.unmount()
  })

  it('shows the server message of a rejected reply inline, keeps the text and retries with the same key', async () => {
    const postCaseMessage = vi
      .fn()
      .mockResolvedValueOnce(fail('a message must not contain a Ban ID', 400, 'VALIDATION'))
      .mockResolvedValueOnce(ok({ messageId: 'new', seq: 9, repeated: false }))
    const { mount } = setup({ extra: { postCaseMessage } })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="message-text"]').setValue('ABCD2345')
    await wrapper.get('[data-testid="message-composer"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="message-error"]').text()).toBe('a message must not contain a Ban ID')
    expect((wrapper.get('[data-testid="message-text"]').element as HTMLTextAreaElement).value).toBe('ABCD2345')

    await wrapper.get('[data-testid="message-composer"]').trigger('submit')
    await flushPromises()

    expect(postCaseMessage.mock.calls[1]![2], 'a retry reuses the key so it can never post twice').toBe(postCaseMessage.mock.calls[0]![2])

    wrapper.unmount()
  })

  it('gives a viewer the thread without a composer or any action', async () => {
    const { mount } = setup({ roles: ['VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.findAll('[data-testid="thread"] > li')).toHaveLength(3)
    expect(wrapper.find('[data-testid="message-composer"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="conversation-read-only"]').text()).toContain('moderator role')
    for (const action of ['message-edit', 'message-delete', 'message-redact', 'erase-player']) {
      expect(wrapper.find(`[data-testid="${action}"]`).exists(), action).toBe(false)
    }

    wrapper.unmount()
  })
})

describe('ConversationSection: own messages', () => {
  it('offers edit and delete on a moderator’s own message only', async () => {
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find(`[data-testid="message-${MINE}"] [data-testid="message-edit"]`).exists()).toBe(true)
    expect(wrapper.find(`[data-testid="message-${MINE}"] [data-testid="message-delete"]`).exists()).toBe(true)
    expect(wrapper.find(`[data-testid="message-${THEIRS}"] [data-testid="message-edit"]`).exists(), 'a colleague’s message').toBe(false)
    expect(wrapper.find(`[data-testid="message-${PLAYER_MSG}"] [data-testid="message-edit"]`).exists(), 'the player’s message').toBe(false)
    expect(wrapper.find('[data-testid="message-redact"]').exists(), 'redaction is for admins').toBe(false)

    wrapper.unmount()
  })

  it('edits a message in place and reloads the thread', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="message-${MINE}"] [data-testid="message-edit"]`).trigger('click')
    const field = wrapper.get(`[data-testid="message-${MINE}"] [data-testid="message-text"]`)
    expect((field.element as HTMLTextAreaElement).value).toBe('We will')
    await field.setValue('We will, today')
    await wrapper.get(`[data-testid="message-${MINE}"] [data-testid="message-composer"]`).trigger('submit')
    await flushPromises()

    expect(api.editCaseMessage).toHaveBeenCalledWith('p-0001', MINE, 'We will, today')
    expect(api.caseConversation).toHaveBeenCalledTimes(2)
    expect(wrapper.find(`[data-testid="message-${MINE}"] [data-testid="message-composer"]`).exists(), 'the editor closed').toBe(false)

    wrapper.unmount()
  })

  it('asks before it deletes, then deletes', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="message-${MINE}"] [data-testid="message-delete"]`).trigger('click')
    expect(api.deleteCaseMessage, 'one click only asks').not.toHaveBeenCalled()
    expect(wrapper.get(`[data-testid="message-${MINE}"]`).text()).toContain('Delete this message?')
    await wrapper.get('[data-testid="message-delete-confirm"]').trigger('click')
    await flushPromises()

    expect(api.deleteCaseMessage).toHaveBeenCalledWith('p-0001', MINE)
    expect(wrapper.emitted('changed')?.[0]).toEqual(['Message deleted.'])

    wrapper.unmount()
  })

  it('shows why a delete failed and keeps the question open', async () => {
    const deleteCaseMessage = vi.fn(async () => fail('only the author may delete', 403, 'UNKNOWN'))
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'], extra: { deleteCaseMessage } })
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="message-${MINE}"] [data-testid="message-delete"]`).trigger('click')
    await wrapper.get('[data-testid="message-delete-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="delete-error"]').text()).toBe('only the author may delete')

    wrapper.unmount()
  })

  it('lets an admin correct a colleague’s staff message but never the player’s', async () => {
    const { mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find(`[data-testid="message-${THEIRS}"] [data-testid="message-edit"]`).exists()).toBe(true)
    expect(wrapper.find(`[data-testid="message-${PLAYER_MSG}"] [data-testid="message-edit"]`).exists()).toBe(false)

    wrapper.unmount()
  })

  it('offers no change on a deleted or redacted message', async () => {
    const events = [
      message(1, MINE, 'gone', 'ME'),
      timelineItem({ seq: 2, type: 'message.deleted', subjectId: MINE, payload: { messageId: MINE } }),
      message(3, THEIRS, 'secret', 'OTHER'),
      timelineItem({ seq: 4, type: 'message.redacted', subjectId: THEIRS, payload: { messageId: THEIRS, reason: 'OTHER' } }),
    ]
    const { mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'], events })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="message-edit"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="message-delete"]').exists()).toBe(false)
    // The text of a deleted message still exists for staff, so an admin may remove it; a redacted one has none left.
    expect(wrapper.findAll('[data-testid="message-redact"]').map((button) => button.element.closest('li')?.getAttribute('data-testid'))).toEqual([`message-${MINE}`])

    wrapper.unmount()
  })
})

describe('ConversationSection: redaction as admin', () => {
  it('removes the text of one message after confirming with a reason', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="message-${THEIRS}"] [data-testid="message-redact"]`).trigger('click')
    await flushPromises()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'PERSONAL_DATA')
    await wrapper.get('[data-testid="redact-message-confirm"]').trigger('click')
    await flushPromises()

    expect(api.redactCaseMessage).toHaveBeenCalledWith('p-0001', THEIRS, 'PERSONAL_DATA')
    expect(wrapper.emitted('changed')?.[0]).toEqual(['Message text removed.'])

    wrapper.unmount()
  })

  it('does not call the server until a reason is chosen', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="message-${THEIRS}"] [data-testid="message-redact"]`).trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="redact-message-confirm"]').attributes('disabled')).toBeDefined()
    expect(api.redactCaseMessage).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('erases the player’s side with a reason', async () => {
    const { api, mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="erase-player"]').trigger('click')
    await flushPromises()
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'ERASURE_REQUEST')
    await wrapper.get('[data-testid="erase-player-confirm"]').trigger('click')
    await flushPromises()

    expect(api.eraseCasePlayerMessages).toHaveBeenCalledWith('p-0001', 'ERASURE_REQUEST')

    wrapper.unmount()
  })

  it('offers no erase when the player wrote nothing', async () => {
    const { mount } = setup({ roles: ['ADMIN', 'MODERATOR', 'VIEWER'], events: [message(1, MINE, 'only staff', 'ME')] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="erase-player"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
