import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

const ID = '5e8f2c10-0000-4000-8000-0000000000c1'

function apiWith(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const path = (call: { url: string }) => new URL(call.url).pathname

describe('exemption review by id', () => {
  it('reads one review from GET /exemption/reviews/{id} and returns the review inside the response', async () => {
    const review = { id: ID, owner: 'ab'.repeat(64), section: 'network', tier: 'PERMANENT', scoreAfter: 250, crossedAt: 1, state: 'OPEN' }
    const { api, calls } = apiWith([{ body: { review } }])

    const result = await api.exemptionReview(ID)

    expect(result).toEqual({ ok: true, data: review })
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['GET', `/api/exemption/reviews/${ID}`])
  })

  it('reports an unknown review as NOT_FOUND so the page can say so', async () => {
    const { api } = apiWith([{ status: 404, body: { errorMessage: 'No such review', kind: 'NOT_FOUND' } }])

    const result = await api.exemptionReview(ID)

    expect(result).toEqual({ ok: false, error: { status: 404, message: 'No such review', kind: 'NOT_FOUND' } })
  })
})
