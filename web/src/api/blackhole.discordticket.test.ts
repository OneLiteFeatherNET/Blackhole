import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const PARTICIPANT = '3f2b8c1e-0000-4000-8000-000000000031'
const route = (call: { method: string; url: string }) => `${call.method} ${new URL(call.url).pathname}`

describe('discord tickets on the case', () => {
  it('erases a participant on its own route without a body', async () => {
    const { calls, api: client } = api([{ body: { erased: true } }])

    const result = await client.eraseCaseParticipant('p-1', PARTICIPANT)

    expect(route(calls[0]!)).toBe(`POST /api/case/p-1/participants/${PARTICIPANT}/erase`)
    expect(calls[0]!.body).toBe('')
    expect(result).toEqual({ ok: true, data: { erased: true } })
  })

  it('reports a failed erasure with the status of the server', async () => {
    const { api: client } = api([{ status: 404, body: { errorMessage: 'Unknown participant', kind: 'NOT_FOUND' } }])

    const result = await client.eraseCaseParticipant('p-1', PARTICIPANT)

    expect(result.ok).toBe(false)
    expect(!result.ok && result.error.status).toBe(404)
  })

  it('locks the appeal ticket of the case on the freeze route with the body frozen', async () => {
    const { calls, api: client } = api([{ body: { frozen: true, freezeSource: 'STAFF' } }])

    const result = await client.setStaffTicketFreeze('p-1', true)

    expect(route(calls[0]!)).toBe('PUT /api/case/p-1/ticket/freeze')
    expect(JSON.parse(String(calls[0]!.body))).toEqual({ frozen: true })
    expect(result).toEqual({ ok: true, data: { frozen: true, freezeSource: 'STAFF' } })
  })

  it('reports a staff unlock refused for a leak freeze with the server text and status', async () => {
    const { api: client } = api([{ status: 409, body: { errorMessage: 'the channel was locked by the leak check', kind: 'CONFLICT', code: 'FREEZE_BY_LEAK' } }])

    const result = await client.setStaffTicketFreeze('p-1', false)

    expect(result.ok).toBe(false)
    expect(!result.ok && result.error.status).toBe(409)
    expect(!result.ok && result.error.message).toBe('the channel was locked by the leak check')
  })

  it('lists the stored protocols of the case', async () => {
    const version = { appealId: PARTICIPANT, version: 2, evidenceId: PARTICIPANT, current: true, entryCount: 4, sourceDigest: 'abc', createdAt: 1 }
    const { calls, api: client } = api([{ body: { items: [version] } }])

    const result = await client.caseProtocols('p-1')

    expect(route(calls[0]!)).toBe('GET /api/case/p-1/protocol')
    expect(result).toEqual({ ok: true, data: [version] })
  })
})
