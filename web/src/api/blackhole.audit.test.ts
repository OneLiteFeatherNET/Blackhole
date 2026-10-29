import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

const OID = '00000000-0000-4000-8000-0000000000b2'

function apiWith(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

describe('audit log calls', () => {
  it('reads the first page from GET /admin/audit without any filter in the query', async () => {
    const { api, calls } = apiWith([{ body: { items: [], nextCursor: null } }])

    const result = await api.auditLog({ limit: 50 })

    expect(result).toEqual({ ok: true, data: { items: [], nextCursor: null } })
    expect(new URL(calls[0]!.url).pathname).toBe('/api/admin/audit')
    expect(new URL(calls[0]!.url).search).toBe('?limit=50')
  })

  it('sends the cursor and every filter the route supports as query parameters', async () => {
    const { api, calls } = apiWith([{ body: { items: [], nextCursor: null } }])

    await api.auditLog({ limit: 50, cursor: 'c-9', action: 'punishment.revoke', actorOid: OID, targetRef: 'ABC123' })

    const query = new URL(calls[0]!.url).searchParams
    expect(query.get('cursor')).toBe('c-9')
    expect(query.get('action')).toBe('punishment.revoke')
    expect(query.get('actorOid')).toBe(OID)
    expect(query.get('targetRef')).toBe('ABC123')
  })

  it('leaves out a filter that is not given', async () => {
    const { api, calls } = apiWith([{ body: { items: [], nextCursor: null } }])

    await api.auditLog({ limit: 50, action: 'kick.mass' })

    expect(new URL(calls[0]!.url).searchParams.has('actorOid')).toBe(false)
    expect(new URL(calls[0]!.url).searchParams.has('cursor')).toBe(false)
  })
})
