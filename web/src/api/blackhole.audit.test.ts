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

describe('one audit entry', () => {
  const AUDIT_ID = '11111111-1111-4111-8111-111111111111'

  it('reads one entry from GET /admin/audit/{auditId} by its id', async () => {
    const entry = { auditId: AUDIT_ID, occurredAt: 1, actorOid: OID, surface: 'PANEL', action: 'kick.mass', targetKind: 'SECTION', recordedByOid: OID }
    const { api, calls } = apiWith([{ body: entry }])

    const result = await api.auditEntry(AUDIT_ID)

    expect(result).toEqual({ ok: true, data: entry })
    expect(new URL(calls[0]!.url).pathname).toBe(`/api/admin/audit/${AUDIT_ID}`)
  })

  it('answers NOT_FOUND for an id that is not an entry', async () => {
    const { api } = apiWith([{ status: 404, body: { errorMessage: 'no such entry', kind: 'NOT_FOUND' } }])

    const result = await api.auditEntry(AUDIT_ID)

    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error.kind).toBe('NOT_FOUND')
  })
})
