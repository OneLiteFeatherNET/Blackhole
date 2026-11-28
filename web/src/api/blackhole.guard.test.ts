import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const sent = (call: { body: string }) => JSON.parse(call.body) as Record<string, unknown>

describe('join guard calls', () => {
  it('reads the manual, automatic and effective level', async () => {
    const { calls, api: client } = api([{ body: { manualLevel: 'OFF', automaticLevel: 'STRICT', effectiveLevel: 'STRICT', updatedAt: 1 } }])

    const result = await client.joinGuard()

    expect(calls[0]!.method).toBe('GET')
    expect(calls[0]!.url).toBe('http://ui.test/api/guard/state')
    expect(result).toEqual({ ok: true, data: { manualLevel: 'OFF', automaticLevel: 'STRICT', effectiveLevel: 'STRICT', updatedAt: 1 } })
  })

  it('sends the chosen manual level', async () => {
    const { calls, api: client } = api([{ body: { manualLevel: 'LOCKDOWN', automaticLevel: 'OFF', effectiveLevel: 'LOCKDOWN', updatedAt: 2 } }])

    await client.setJoinGuardLevel('LOCKDOWN')

    expect(calls[0]!.method).toBe('POST')
    expect(calls[0]!.url).toBe('http://ui.test/api/guard/level')
    expect(sent(calls[0]!)).toEqual({ level: 'LOCKDOWN' })
  })
})

describe('area ban calls', () => {
  it('lists only the bans in force unless inactive ones are asked for', async () => {
    const { calls, api: client } = api([{ body: { bans: [] } }, { body: { bans: [] } }])

    await client.areaBans()
    await client.areaBans(true)

    expect(calls[0]!.url).toBe('http://ui.test/api/area-bans?includeInactive=false')
    expect(calls[1]!.url).toBe('http://ui.test/api/area-bans?includeInactive=true')
  })

  it('answers an empty list when the backend leaves bans out of its JSON', async () => {
    const { api: client } = api([{ body: {} }])

    expect(await client.areaBans()).toEqual({ ok: true, data: [] })
  })

  it('sends a timed ASN ban with trimmed value and reason', async () => {
    const { calls, api: client } = api([{ body: { id: 'b-1' } }])

    await client.createAreaBan({ kind: 'ASN', value: ' AS1234 ', reason: ' Proxy farm ', durationSeconds: 604800 })

    expect(calls[0]!.method).toBe('POST')
    expect(calls[0]!.url).toBe('http://ui.test/api/area-bans')
    expect(sent(calls[0]!)).toEqual({ kind: 'ASN', value: 'AS1234', reason: 'Proxy farm', durationSeconds: 604800 })
  })

  it('omits the duration of a permanent ban', async () => {
    const { calls, api: client } = api([{ body: { id: 'b-2' } }])

    await client.createAreaBan({ kind: 'COUNTRY', value: 'XX', reason: 'Fraud', durationSeconds: null })

    expect(sent(calls[0]!)).toEqual({ kind: 'COUNTRY', value: 'XX', reason: 'Fraud' })
  })

  it('posts the lift with the trimmed reason to the ban', async () => {
    const { calls, api: client } = api([{ body: { id: 'b-1', active: false } }])

    await client.liftAreaBan('b-1', ' False positive ')

    expect(calls[0]!.method).toBe('POST')
    expect(calls[0]!.url).toBe('http://ui.test/api/area-bans/b-1/lift')
    expect(sent(calls[0]!)).toEqual({ reason: 'False positive' })
  })
})
