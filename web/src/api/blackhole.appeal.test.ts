import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const ID = '3f2b8c1e-0000-4000-8000-000000000001'

function appeal(identifier: string) {
  return { identifier, punishmentIdentifier: 'p-0001', appellantHash: 'cd'.repeat(64), statement: 'Not me', status: 'SUBMITTED', eligibilityCheckResult: {}, verified: false, evidenceCount: 0, createdAt: 1, updatedAt: 1, metaData: {} }
}

describe('appeal reads', () => {
  it('reads one appeal by its identifier', async () => {
    const { calls, api: client } = api([{ body: appeal(ID) }])

    const result = await client.appeal(ID)

    expect(calls[0]!.url).toBe(`http://ui.test/api/appeal/${ID}`)
    expect(calls[0]!.method).toBe('GET')
    expect(result.ok && result.data.identifier).toBe(ID)
  })

  it('maps an unknown appeal to NOT_FOUND', async () => {
    const { api: client } = api([{ status: 404, body: { errorMessage: 'there is no appeal with this identifier', kind: 'NOT_FOUND' } }])

    const result = await client.appeal(ID)

    expect(result).toEqual({ ok: false, error: { status: 404, message: 'there is no appeal with this identifier', kind: 'NOT_FOUND' } })
  })
})
