import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SessionPage, SessionResponse } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import PlayerSessions from './PlayerSessions.vue'

const OWNER = 'ab'.repeat(64)
const UUID = '11111111-2222-3333-4444-555555555555'

function session(overrides: Partial<SessionResponse> = {}): SessionResponse {
  return {
    sessionId: 's-1',
    owner: OWNER,
    playerUuid: UUID,
    playerName: 'Steve',
    ip: '203.0.113.7',
    asn: 64500,
    asOrg: 'Example Transit',
    countryCode: 'DE',
    clientBrand: 'vanilla',
    clientVersion: '1.21.4',
    protocolVersion: 769,
    virtualHost: 'play.example.net',
    outcome: 'ALLOWED',
    startedAt: Date.UTC(2030, 0, 2, 12, 0),
    endedAt: null,
    ...overrides,
  }
}

function page(sessions: SessionResponse[], hasMore = false, pageNumber = 0): SessionPage {
  return { sessions, page: pageNumber, size: 50, hasMore }
}

function api(overrides: Record<string, unknown> = {}) {
  return mockApi({ playerSessions: vi.fn(async () => ok(page([session()]))), ...overrides })
}

async function open(overrides: Record<string, unknown> = {}) {
  return mountWithApp(PlayerSessions, { props: { owner: OWNER }, api: api(overrides) })
}

describe('PlayerSessions', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('shows the player UUID and name in the section header', async () => {
    const { wrapper } = await open()

    const header = wrapper.get('[data-testid="sessions-identity"]').text()
    expect(header).toContain(UUID)
    expect(header).toContain('Steve')

    wrapper.unmount()
  })

  it('lists each session with its time, outcome, address, provider, country, client, protocol and host', async () => {
    const { wrapper } = await open()

    const rows = wrapper.get('table[aria-label="Sessions"]').findAll('tbody tr')
    expect(rows).toHaveLength(1)
    const text = rows[0]!.text()
    expect(text, 'time').toContain('Jan 2, 2030')
    expect(text, 'outcome').toContain('Allowed')
    expect(text, 'address').toContain('203.0.113.7')
    expect(text, 'provider').toContain('Example Transit')
    expect(text, 'country, marked as approximate').toContain('DE (approximate)')
    expect(text, 'client brand and version').toContain('vanilla 1.21.4')
    expect(text, 'protocol').toContain('769')
    expect(text, 'virtual host').toContain('play.example.net')

    wrapper.unmount()
  })

  it('lists a denied session with its outcome', async () => {
    const { wrapper } = await open({ playerSessions: vi.fn(async () => ok(page([session({ outcome: 'DENIED' })]))) })

    expect(wrapper.get('table[aria-label="Sessions"] tbody tr').text()).toContain('Denied')

    wrapper.unmount()
  })

  it('shows a dash for enrichment and client data that is missing and still lists the row', async () => {
    const bare = session({ asn: null, asOrg: null, countryCode: null, clientBrand: null, clientVersion: null, protocolVersion: null, virtualHost: null })
    const { wrapper } = await open({ playerSessions: vi.fn(async () => ok(page([bare]))) })

    const row = wrapper.get('table[aria-label="Sessions"] tbody tr')
    expect(row.text()).toContain('203.0.113.7')
    expect(row.findAll('td').filter((cell) => cell.text() === '–'), 'provider, country, client, protocol, host').toHaveLength(5)

    wrapper.unmount()
  })

  it('shows the empty state when no session has been recorded', async () => {
    const { wrapper } = await open({ playerSessions: vi.fn(async () => ok(page([]))) })

    expect(wrapper.text()).toContain('No session has been recorded yet.')
    expect(wrapper.find('table[aria-label="Sessions"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the backend error with a retry when the request fails', async () => {
    const playerSessions = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok(page([session()])))
    const { wrapper } = await open({ playerSessions })

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('table[aria-label="Sessions"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('appends the next page when the backend has more sessions', async () => {
    const playerSessions = vi.fn(async (_owner: string, pageNumber: number) =>
      ok(pageNumber === 0 ? page([session({ sessionId: 's-2' })], true, 0) : page([session({ sessionId: 's-1', ip: '198.51.100.9' })], false, 1)),
    )
    const { wrapper } = await open({ playerSessions })

    await wrapper.get('[data-testid="load-more"]').trigger('click')
    await flushPromises()

    expect(playerSessions).toHaveBeenLastCalledWith(OWNER, 1, 50)
    expect(wrapper.findAll('table[aria-label="Sessions"] tbody tr')).toHaveLength(2)
    expect(wrapper.find('[data-testid="load-more"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the DB-IP attribution in the footer', async () => {
    const { wrapper } = await open()

    const footer = wrapper.get('[data-testid="sessions-attribution"]')
    expect(footer.text()).toContain('DB-IP')
    expect(footer.get('a').attributes('href')).toBe('https://db-ip.com')

    wrapper.unmount()
  })

  it('keeps the sessions out of browser storage', async () => {
    const { wrapper } = await open()
    await flushPromises()

    const stored = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage })
    expect(stored).not.toContain('203.0.113.7')
    expect(stored).not.toContain('Example Transit')
    expect(stored).not.toContain(UUID)

    wrapper.unmount()
  })
})
