import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function apiWith(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

describe('punishmentsByShortId', () => {
  it('encodes the # in the path and returns every match', async () => {
    const matches = [
      { identifier: 'p-1', shortId: '#AB12CD34' },
      { identifier: 'p-2', shortId: '#AB12CD34' },
    ]
    const { api, calls } = apiWith([{ body: matches }])

    const result = await api.punishmentsByShortId('#AB12CD34')

    expect(calls[0]!.url).toBe('http://ui.test/api/punishment/short/%23AB12CD34')
    expect(result).toEqual({ ok: true, data: matches })
  })

  it('reports a malformed short id as an error', async () => {
    const { api } = apiWith([{ status: 400, body: { message: 'Not a short ID' } }])

    const result = await api.punishmentsByShortId('#zz')

    expect(result.ok).toBe(false)
  })
})
