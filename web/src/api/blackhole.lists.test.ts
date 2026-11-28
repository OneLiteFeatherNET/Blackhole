import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

const OWNER = 'a'.repeat(128)

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

describe('moderation list read-by-id calls', () => {
  it('reads one IP ban by its id', async () => {
    const { calls, api: client } = api([{ body: { id: 'b1', range: '203.0.113.7', reason: 'x', source: 'PANEL', createdAt: 1, warnings: [] } }])

    const result = await client.ipBan('b1')

    expect(result.ok).toBe(true)
    expect(calls[0]!.method).toBe('GET')
    expect(calls[0]!.url).toBe('http://ui.test/api/ipban/b1')
  })

  it('reads one area ban by its id', async () => {
    const { calls, api: client } = api([{ body: { id: 'a1', kind: 'ASN', value: 'AS4134', reason: 'x', source: 'manual', createdAt: 1, active: true } }])

    await client.areaBan('a1')

    expect(calls[0]!.url).toBe('http://ui.test/api/area-bans/a1')
  })

  it('reads one allow-list entry by the owner hash', async () => {
    const { calls, api: client } = api([{ body: { owner: OWNER, reason: 'x', actorOid: 'o', createdAt: 1, expired: false } }])

    await client.intelAllowListEntry(OWNER)

    expect(calls[0]!.url).toBe(`http://ui.test/api/intel/allow-list/${OWNER}`)
  })

  it('reads one page of the allow list with its page and size', async () => {
    const { calls, api: client } = api([{ body: { entries: [], page: 2, size: 25, hasMore: false } }])

    const result = await client.intelAllowList(2, 25)

    expect(calls[0]!.url).toBe('http://ui.test/api/intel/allow-list?page=2&size=25')
    expect(result.ok && result.data.entries).toEqual([])
  })

  it('removes an allow-list entry by the owner hash', async () => {
    const { calls, api: client } = api([{ body: { owner: OWNER } }])

    await client.removeIntelAllowListEntry(OWNER)

    expect(calls[0]!.method).toBe('DELETE')
    expect(calls[0]!.url).toBe(`http://ui.test/api/intel/allow-list/${OWNER}`)
  })
})
