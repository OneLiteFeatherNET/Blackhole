import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

const OID = '00000000-0000-4000-8000-0000000000b2'

function apiWith(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const path = (call: { url: string }) => new URL(call.url).pathname

describe('staff link calls', () => {
  it('issues a code with POST /staff-link/code and returns it as the backend formats it', async () => {
    const { api, calls } = apiWith([{ body: { code: 'ABCD-EFGH', expiresAt: 1_000 } }])

    const result = await api.issueStaffLinkCode()

    expect(result, 'the code is passed through unchanged').toEqual({ ok: true, data: { code: 'ABCD-EFGH', expiresAt: 1_000 } })
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['POST', '/api/staff-link/code'])
  })

  it('reads the own link status from GET /staff-link/me', async () => {
    const { api, calls } = apiWith([{ body: { linked: false } }])

    const result = await api.staffLinkStatus()

    expect(result).toEqual({ ok: true, data: { linked: false } })
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['GET', '/api/staff-link/me'])
  })

  it('removes the own link with DELETE /staff-link/me', async () => {
    const { api, calls } = apiWith([{ body: { oid: OID } }])

    const result = await api.unlinkOwnStaffLink()

    expect(result.ok, 'the removal succeeded').toBe(true)
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['DELETE', '/api/staff-link/me'])
  })

  it('lists all links as plain items from GET /admin/staff-link', async () => {
    const item = { oid: OID, displayName: 'Ada', minecraftUuid: '11111111-1111-4111-8111-111111111111', linkedAt: 5 }
    const { api, calls } = apiWith([{ body: { items: [item] } }])

    const result = await api.staffLinks()

    expect(result).toEqual({ ok: true, data: [item] })
    expect(path(calls[0]!)).toBe('/api/admin/staff-link')
  })

  it('removes anyone with DELETE /admin/staff-link/{oid}', async () => {
    const { api, calls } = apiWith([{ body: { oid: OID } }])

    await api.unlinkStaffLink(OID)

    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['DELETE', `/api/admin/staff-link/${OID}`])
  })

  it('reports a 403 as FORBIDDEN', async () => {
    const { api } = apiWith([{ status: 403, body: { errorMessage: 'no', kind: 'FORBIDDEN' } }])

    const result = await api.staffLinks()

    expect(!result.ok && result.error.kind, 'a moderator is refused').toBe('FORBIDDEN')
  })
})
