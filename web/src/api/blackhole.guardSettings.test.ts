import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const sent = (call: { body: string }) => JSON.parse(call.body) as Record<string, unknown>

const SETTINGS = {
  unattendedLevel: 'STRICT',
  absenceThresholdMinutes: 20,
  nightWindowEnabled: true,
  nightWindowStart: '01:00',
  nightWindowEnd: '07:00',
  timeZone: 'Europe/Berlin',
  updatedAt: 1,
  updatedByOid: null,
  nextNightWindowStart: 2,
  nextNightWindowEnd: 3,
}

describe('join guard settings calls', () => {
  it('reads the stored settings with the next night window', async () => {
    const { calls, api: client } = api([{ body: SETTINGS }])

    const result = await client.joinGuardSettings()

    expect(calls[0]!.method).toBe('GET')
    expect(calls[0]!.url).toBe('http://ui.test/api/guard/settings')
    expect(result).toEqual({ ok: true, data: SETTINGS })
  })

  it('sends the whole settings object unchanged when saving', async () => {
    const request = {
      unattendedLevel: 'LOCKDOWN',
      absenceThresholdMinutes: 45,
      nightWindowEnabled: false,
      nightWindowStart: '23:00',
      nightWindowEnd: '02:00',
      timeZone: 'Europe/Paris',
    } as const
    const { calls, api: client } = api([{ body: { ...SETTINGS, ...request } }])

    await client.setJoinGuardSettings(request)

    expect(calls[0]!.method).toBe('PUT')
    expect(calls[0]!.url).toBe('http://ui.test/api/guard/settings')
    expect(sent(calls[0]!)).toEqual(request)
  })

  it('maps a refused value to the validation error of the server', async () => {
    const { api: client } = api([{ status: 400, body: { errorMessage: 'OFF is refused', kind: 'VALIDATION' } }])

    const result = await client.setJoinGuardSettings({
      unattendedLevel: 'OFF',
      absenceThresholdMinutes: 20,
      nightWindowEnabled: true,
      nightWindowStart: '01:00',
      nightWindowEnd: '07:00',
      timeZone: 'Europe/Berlin',
    })

    expect(result).toEqual({ ok: false, error: { status: 400, message: 'OFF is refused', kind: 'VALIDATION' } })
  })
})
