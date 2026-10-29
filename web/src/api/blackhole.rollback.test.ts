import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

const OID = '00000000-0000-4000-8000-0000000000b2'
const JOB = '6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f'

function apiWith(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

describe('rollback calls', () => {
  it('posts the staff selector and the window to the preview route', async () => {
    const { api, calls } = apiWith([{ status: 201, body: { jobId: JOB, state: 'PREVIEW', previewExpiresAt: 1, revocable: 3, alreadyInactive: 1, players: 2, kicks: 1, byKind: {}, bySection: {} } }])

    const result = await api.previewRollback({ staffOid: OID, windowFrom: 10, windowTo: 20 })

    expect(result.ok && result.data.revocable).toBe(3)
    expect(new URL(calls[0]!.url).pathname).toBe('/api/admin/rollback/preview')
    expect(calls[0]!.method).toBe('POST')
    expect(JSON.parse(calls[0]!.body)).toEqual({ staffOid: OID, windowFrom: 10, windowTo: 20 })
  })

  it('keeps the rollback refusal kind so the screen can name the problem', async () => {
    const { api } = apiWith([{ status: 422, body: { errorMessage: 'more than 5000 match', kind: 'ROLLBACK_TOO_LARGE' } }])

    const result = await api.previewRollback({ staffOid: OID, windowFrom: 10, windowTo: 20 })

    expect(result).toEqual({ ok: false, error: { status: 422, message: 'more than 5000 match', kind: 'ROLLBACK_TOO_LARGE' } })
  })

  it('sends the Idempotency-Key header and the options on execute', async () => {
    const { api, calls } = apiWith([{ status: 202, body: { jobId: JOB, state: 'RUNNING', repeated: false } }])

    await api.executeRollback(JOB, { reason: 'account taken over', suspendLink: true, includeMarks: false }, 'key-1')

    expect(new URL(calls[0]!.url).pathname).toBe(`/api/admin/rollback/${JOB}/execute`)
    expect(calls[0]!.headers.get('Idempotency-Key')).toBe('key-1')
    expect(JSON.parse(calls[0]!.body)).toEqual({ reason: 'account taken over', suspendLink: true, includeMarks: false })
  })

  it('reads the progress with GET and cancels with POST', async () => {
    const { api, calls } = apiWith([
      { body: { jobId: JOB, state: 'RUNNING', items: { PENDING: 2, REVOKED: 1 } } },
      { body: { jobId: JOB, state: 'CANCELLED' } },
    ])

    await api.rollbackProgress(JOB)
    await api.cancelRollback(JOB)

    expect(calls[0]!.method).toBe('GET')
    expect(new URL(calls[0]!.url).pathname).toBe(`/api/admin/rollback/${JOB}`)
    expect(calls[1]!.method).toBe('POST')
    expect(new URL(calls[1]!.url).pathname).toBe(`/api/admin/rollback/${JOB}/cancel`)
  })
})
