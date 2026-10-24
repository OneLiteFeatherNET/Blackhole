import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CaseTimelineItem } from '../../api/types'
import { caseResponse, OTHER, timelineItem } from '../../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import ConversationSection from './ConversationSection.vue'

const TICKET = '3f2b8c1e-0000-4000-8000-0000000000a1'
const APPEAL = '3f2b8c1e-0000-4000-8000-0000000000a2'
const PLAYER_PART = '3f2b8c1e-0000-4000-8000-0000000000c1'
const STAFF_PART = '3f2b8c1e-0000-4000-8000-0000000000c2'
const BOT_PART = '3f2b8c1e-0000-4000-8000-0000000000c3'
const ALL = ['ADMIN', 'MODERATOR', 'VIEWER']

function discordMessage(seq: number, id: string, kind: 'PLAYER' | 'STAFF' | 'BOT' | 'EXTERNAL', participantId: string, extra: Record<string, unknown> = {}, trust: 'PLAYER' | 'VERIFIED' | 'UNVERIFIED' = 'UNVERIFIED') {
  return timelineItem({
    seq,
    type: 'message.added',
    subjectId: id,
    visibility: 'PLAYER',
    actor: kind === 'STAFF' && trust === 'VERIFIED' ? { oid: OTHER, name: 'Ada', trust } : { trust: kind === 'PLAYER' ? 'PLAYER' : trust },
    payload: { messageId: id, author: { kind, participantId }, channel: 'DISCORD', text: `from ${kind}`, ...extra },
  })
}

const opened = () => timelineItem({ seq: 1, type: 'ticket.opened', payload: { ticketId: TICKET, appealId: APPEAL, ticketNo: 12 } })

function setup(events: CaseTimelineItem[], roles = ALL, extra: Record<string, unknown> = {}, banIdLeakedAt?: number) {
  const api = {
    caseConversation: vi.fn(async () => ok(events)),
    openCaseEvents: () => ({ close() {} }),
    eraseCaseParticipant: vi.fn(async () => ok({ erased: true })),
    caseProtocols: vi.fn(async () => ok([])),
    ...extra,
  }
  return { api, mount: () => mountWithApp(ConversationSection, { api: mockApi(api), roles, props: { kase: caseResponse({ banIdLeakedAt }) } }) }
}

describe('ConversationSection: Discord labels', () => {
  const events = [
    opened(),
    discordMessage(2, 'm-player', 'PLAYER', PLAYER_PART),
    discordMessage(3, 'm-unverified', 'STAFF', STAFF_PART),
    discordMessage(4, 'm-verified', 'STAFF', STAFF_PART, {}, 'VERIFIED'),
    discordMessage(5, 'm-bot', 'BOT', BOT_PART, { attachments: [{ kind: 'IMAGE', name: 'a.png' }], embeds: [{ title: 'x' }] }),
  ]

  it('labels Discord messages with the channel and the author trust, and shows no Discord or participant id', async () => {
    const { mount } = setup(events)
    const { wrapper } = await mount()

    const pills = (id: string) => wrapper.get(`[data-testid="message-${id}"]`)
    expect(pills('m-player').get('[data-testid="from-discord"]').text()).toBe('Discord')
    expect(pills('m-player').get('[data-testid="from-player"]').text()).toContain('not verified')
    expect(pills('m-unverified').get('[data-testid="discord-trust"]').text()).toBe('Unverified staff (not linked)')
    expect(pills('m-verified').get('[data-testid="discord-trust"]').text()).toBe('Verified staff (linked account)')
    expect(pills('m-verified').get('[data-testid="message-author"]').text()).toBe('Ada')
    expect(pills('m-bot').get('[data-testid="discord-trust"]').text()).toBe('Bot')
    expect(pills('m-bot').get('[data-testid="message-author"]').text()).toBe('Bot')
    expect(pills('m-bot').get('[data-testid="attachments"]').text()).toBe('2 attachments (not stored)')
    for (const id of [PLAYER_PART, STAFF_PART, BOT_PART]) expect(wrapper.text()).not.toContain(id)

    wrapper.unmount()
  })

  it('labels no Discord pill on a portal message', async () => {
    const portal = timelineItem({ seq: 2, type: 'message.added', subjectId: 'm-1', payload: { messageId: 'm-1', author: { kind: 'PLAYER' }, channel: 'PORTAL', text: 'hi' } })
    const { mount } = setup([portal])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="from-discord"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="discord-trust"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('offers no edit or delete on a Discord message, even a verified own one', async () => {
    const { mount } = setup(events, ALL)
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="message-m-verified"] [data-testid="message-edit"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="message-m-verified"] [data-testid="message-delete"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('ConversationSection: ticket status', () => {
  it('shows number, state, transcript and protocol of a ticket, with the live region for changes', async () => {
    const decided = timelineItem({ type: 'appeal.decided', payload: { appealId: APPEAL, decision: 'DENIED' } })
    const complete = timelineItem({ type: 'ticket.transcript_complete', payload: { ticketId: TICKET, appealId: APPEAL, count: 2, edits: 0, deletes: 0 } })
    const stored = timelineItem({ type: 'protocol.stored', payload: { appealId: APPEAL, version: 2, evidenceId: 'e', entries: 2, digest: 'd' } })
    const { mount } = setup([opened(), decided, complete, stored])
    const { wrapper } = await mount()

    const ticket = wrapper.get('[data-testid="ticket-12"]')
    expect(ticket.get('h4').text()).toContain('Ticket #12')
    expect(ticket.get('[data-testid="ticket-state"]').text()).toBe('Closed')
    expect(ticket.get('[data-testid="ticket-transcript"]').text()).toBe('Verified complete')
    expect(ticket.get('[data-testid="ticket-protocol"]').text()).toBe('Version 2')
    expect(wrapper.find('[data-testid="ticket-incomplete"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="ticket-protocol-missing"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="conversation-status"]').attributes('aria-live')).toBe('polite')

    wrapper.unmount()
  })

  it('shows the missing-transcript banner as a status when a decided ticket has no completeness event', async () => {
    const decided = timelineItem({ type: 'appeal.decided', payload: { appealId: APPEAL, decision: 'GRANTED_FULL_LIFT' } })
    const { mount } = setup([opened(), decided])
    const { wrapper } = await mount()

    const banner = wrapper.get('[data-testid="ticket-incomplete"]')
    expect(banner.attributes('role')).toBe('status')
    expect(banner.text()).toContain('may be incomplete since')

    wrapper.unmount()
  })

  it('shows the protocol-missing banner when the transcript is complete but no protocol is stored', async () => {
    const complete = timelineItem({ type: 'ticket.transcript_complete', payload: { ticketId: TICKET, appealId: APPEAL, count: 2, edits: 0, deletes: 0 } })
    const { mount } = setup([opened(), complete])
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="ticket-protocol-missing"]').text()).toContain('not archived or deleted until it exists')
    expect(wrapper.get('[data-testid="ticket-protocol"]').text()).toBe('None stored')

    wrapper.unmount()
  })

  it('says the channel was lost early', async () => {
    const lost = timelineItem({ type: 'ticket.channel_deleted', payload: { ticketId: TICKET, appealId: APPEAL, transcriptComplete: false, lastRecordedMessageAt: Date.UTC(2026, 6, 2) } })
    const { mount } = setup([opened(), lost])
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="ticket-incomplete"]').text()).toContain('deleted before the transcript was verified')
    expect(wrapper.get('[data-testid="ticket-state"]').text()).toBe('Channel deleted')

    wrapper.unmount()
  })

  it('marks an open ticket frozen after a leak that follows the filing', async () => {
    const leak = timelineItem({ type: 'ban_id.leak_marked', payload: { reason: 'POSTED_PUBLIC', source: 'DISCORD_BOT', afterFiling: true } })
    const { mount } = setup([opened(), leak], ALL, {}, Date.UTC(2026, 6, 3))
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="ticket-frozen"]').text()).toBe('Frozen')

    wrapper.unmount()
  })

  it('shows nothing for a case without a ticket', async () => {
    const { mount } = setup([])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="tickets"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('loads the stored protocol versions on request and says how many', async () => {
    const version = { appealId: APPEAL, version: 2, evidenceId: 'e-2', current: true, entryCount: 3, sourceDigest: 'd', createdAt: Date.UTC(2026, 6, 2) }
    const caseProtocols = vi.fn(async () => ok([version]))
    const { mount } = setup([opened()], ALL, { caseProtocols })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="protocols-show"]').trigger('click')
    await flushPromises()

    expect(caseProtocols).toHaveBeenCalledWith('p-0001')
    expect(wrapper.get('[data-testid="protocol-2"]').text()).toContain('current')
    expect(wrapper.get('[data-testid="protocols-status"]').text()).toBe('1 protocol versions loaded.')

    wrapper.unmount()
  })

  it('shows why the protocol list could not be loaded', async () => {
    const caseProtocols = vi.fn(async () => fail('not allowed', 403, 'UNKNOWN'))
    const { mount } = setup([opened()], ALL, { caseProtocols })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="protocols-show"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="protocols-error"]').text()).toBe('not allowed')

    wrapper.unmount()
  })
})

describe('ConversationSection: participant erasure', () => {
  const events = [discordMessage(1, 'm-player', 'PLAYER', PLAYER_PART), discordMessage(2, 'm-staff', 'STAFF', STAFF_PART)]

  it('lets an admin erase a participant after confirming, without ever naming an id', async () => {
    const { api, mount } = setup(events)
    const { wrapper } = await mount()

    const rows = wrapper.findAll('[data-testid="participants"] li')
    expect(rows.map((row) => row.get('span').text())).toEqual(['Discord participant 1', 'Discord participant 2'])
    expect(wrapper.get('[data-testid="participants"]').text()).not.toContain(PLAYER_PART)

    await wrapper.get('[data-testid="participant-1"] [data-testid="participant-erase"]').trigger('click')
    await flushPromises()
    expect(api.eraseCaseParticipant).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="participant-confirm"]').trigger('click')
    await flushPromises()

    expect(api.eraseCaseParticipant).toHaveBeenCalledWith('p-0001', PLAYER_PART)
    expect(wrapper.emitted('changed')?.[0]).toEqual(['Participant erased.'])
    expect(wrapper.get('[data-testid="participant-1"] [data-testid="participant-erased"]').text()).toBe('Erased')

    wrapper.unmount()
  })

  it('keeps the dialog open and shows the server message when the erasure fails', async () => {
    const eraseCaseParticipant = vi.fn(async () => fail('Unknown participant', 404, 'NOT_FOUND'))
    const { mount } = setup(events, ALL, { eraseCaseParticipant })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="participant-1"] [data-testid="participant-erase"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="participant-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="participant-error"]').text()).toBe('Unknown participant')
    expect(wrapper.emitted('changed')).toBeUndefined()

    wrapper.unmount()
  })

  it.each([['MODERATOR'], ['VIEWER']])('hides the erasure from a %s', async (role) => {
    const { mount } = setup(events, role === 'MODERATOR' ? ['MODERATOR', 'VIEWER'] : ['VIEWER'])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="participants"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="participant-erase"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('offers no erasure for a case without Discord messages', async () => {
    const portal = timelineItem({ seq: 2, type: 'message.added', subjectId: 'm-1', payload: { messageId: 'm-1', author: { kind: 'PLAYER' }, channel: 'PORTAL', text: 'hi' } })
    const { mount } = setup([portal])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="participants"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
