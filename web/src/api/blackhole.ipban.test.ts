import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const sent = (call: { body: string }) => JSON.parse(call.body) as Record<string, unknown>

describe('IP ban calls', () => {
  it('sends a temporary ban with the panel as its source and the expiry in epoch milliseconds', async () => {
    const { calls, api: client } = api([{ body: { id: 'b1' } }])

    await client.createIpBan({ address: ' 203.0.113.7 ', reason: ' Ban evasion ', expiresAt: 1_700_000_000_000 })

    expect(calls[0]!.method).toBe('POST')
    expect(calls[0]!.url).toBe('http://ui.test/api/ipban')
    expect(sent(calls[0]!)).toEqual({ address: '203.0.113.7', reason: 'Ban evasion', source: 'PANEL', expiresAt: 1_700_000_000_000 })
  })

  it('leaves the expiry out of a permanent ban', async () => {
    const { calls, api: client } = api([{ body: { id: 'b1' } }])

    await client.createIpBan({ address: '2001:db8::/32', reason: 'Botnet', expiresAt: null })

    expect(sent(calls[0]!)).toEqual({ address: '2001:db8::/32', reason: 'Botnet', source: 'PANEL' })
  })

  it('keeps the server rule code of a refusal', async () => {
    const { api: client } = api([{ status: 422, body: { errorMessage: 'The range is protected', kind: 'IP_BAN_NOT_ALLOWED', code: 'IPBAN_PROTECTED' } }])

    const result = await client.createIpBan({ address: '10.0.0.1', reason: 'x', expiresAt: null })

    expect(result).toEqual({ ok: false, error: { status: 422, message: 'The range is protected', kind: 'UNKNOWN', code: 'IPBAN_PROTECTED' } })
  })

  it('unwraps the list of bans', async () => {
    const { calls, api: client } = api([{ body: { bans: [{ id: 'b1', range: '203.0.113.7', reason: 'x', source: 'PANEL', createdAt: 1, warnings: [] }] } }])

    const result = await client.ipBans()

    expect(calls[0]!.url).toBe('http://ui.test/api/ipban')
    expect(result).toMatchObject({ ok: true, data: [{ id: 'b1', range: '203.0.113.7' }] })
  })

  it('posts the trimmed lift reason to the ban it names', async () => {
    const { calls, api: client } = api([{ body: { id: 'b1', liftedAt: 2 } }])

    await client.liftIpBan('b1', ' False positive ')

    expect(calls[0]!.url).toBe('http://ui.test/api/ipban/b1/lift')
    expect(sent(calls[0]!)).toEqual({ reason: 'False positive' })
  })
})
