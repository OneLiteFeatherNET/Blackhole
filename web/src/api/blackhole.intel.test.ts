import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

const OWNER = 'ab'.repeat(64)

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const sent = (call: { body: string }) => JSON.parse(call.body) as Record<string, unknown>

describe('VPN and hosting bodies', () => {
  it('sends a permanent exemption with a null duration', async () => {
    const { calls, api: client } = api([{ body: { owner: OWNER, reason: 'appeal', actorOid: 'x', createdAt: 1, expiresAt: null } }])

    await client.addIntelAllowListEntry({ owner: OWNER, reason: 'appeal', durationDays: null })

    expect(calls[0]!.method).toBe('POST')
    expect(calls[0]!.url).toBe('http://ui.test/api/intel/allow-list')
    expect(sent(calls[0]!)).toEqual({ owner: OWNER, reason: 'appeal', durationDays: null })
  })

  it('sends the class and the policy of a change', async () => {
    const { calls, api: client } = api([{ body: { classification: 'VPN', policy: 'DENY' } }])

    await client.setIntelPolicy('VPN', 'DENY')

    expect(calls[0]!.url).toBe('http://ui.test/api/intel/policy')
    expect(sent(calls[0]!)).toEqual({ classification: 'VPN', policy: 'DENY' })
  })

  it('reads the policies as the list the page shows', async () => {
    const { api: client } = api([{ body: { policies: [{ classification: 'PROXY', policy: 'FLAG' }] } }])

    const result = await client.intelPolicies()

    expect(result).toEqual({ ok: true, data: [{ classification: 'PROXY', policy: 'FLAG' }] })
  })

  it('reads the hits of a player as a list', async () => {
    const hit = { id: 'h1', ip: '203.0.113.7', classification: 'VPN', sourceKey: 'list:x4bnet-vpn', verdict: 'FLAG', allowListed: false, createdAt: 1 }
    const { calls, api: client } = api([{ body: { hits: [hit] } }])

    const result = await client.intelHits(OWNER)

    expect(calls[0]!.url).toBe(`http://ui.test/api/intel/players/${OWNER}/hits`)
    expect(result).toEqual({ ok: true, data: [hit] })
  })
})
