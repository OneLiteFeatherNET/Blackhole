import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function apiWith(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const path = (call: { url: string }) => new URL(call.url).pathname
const version = (n: number) => ({ version: n, text: `text ${n}`, createdAt: '2030-01-01T00:00:00Z' })

describe('appeal conditions calls', () => {
  it('reads the current version with GET /appeal-conditions', async () => {
    const { api, calls } = apiWith([{ body: version(3) }])

    const result = await api.appealConditions()

    expect(result).toEqual({ ok: true, data: version(3) })
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['GET', '/api/appeal-conditions'])
  })

  it('lists the versions with GET /appeal-conditions/versions', async () => {
    const { api, calls } = apiWith([{ body: [version(2), version(1)] }])

    const result = await api.appealConditionVersions()

    expect(result).toEqual({ ok: true, data: [version(2), version(1)] })
    expect(path(calls[0]!)).toBe('/api/appeal-conditions/versions')
  })

  it('saves a new version with POST /appeal-conditions and the text in the body', async () => {
    const { api, calls } = apiWith([{ body: version(4) }])

    const result = await api.saveAppealConditions('new text')

    expect(result.ok, 'the save succeeded').toBe(true)
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['POST', '/api/appeal-conditions'])
    expect(JSON.parse(String(calls[0]!.body))).toEqual({ text: 'new text' })
  })
})
