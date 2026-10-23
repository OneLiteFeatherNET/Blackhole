import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const NOTE = '3f2b8c1e-0000-4000-8000-000000000001'

describe('case reads', () => {
  it('encodes the # of a short id in the path', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.caseDetail('#AB12CD')

    expect(calls[0]!.url).toBe('http://ui.test/api/case/%23AB12CD')
    expect(calls[0]!.method).toBe('GET')
  })

  it('sends one type parameter per group and only the filters that are set', async () => {
    const { calls, api: client } = api([{ body: { items: [], nextCursor: null } }])

    await client.caseTimeline('p-1', { types: ['note', 'appeal'], actorTrust: 'UNVERIFIED', cursor: '1700:7', size: 50, order: 'desc' })

    const url = new URL(calls[0]!.url)
    expect(url.pathname).toBe('/api/case/p-1/timeline')
    expect(url.searchParams.getAll('type')).toEqual(['note', 'appeal'])
    expect(url.searchParams.get('actorTrust')).toBe('UNVERIFIED')
    expect(url.searchParams.get('cursor')).toBe('1700:7')
    expect(url.searchParams.get('order')).toBe('desc')
    expect(url.searchParams.has('from')).toBe(false)
  })

  it('lists cases with the chosen filters', async () => {
    const { calls, api: client } = api([{ body: { items: [] } }])

    await client.cases({ status: 'OPEN', tag: 'spam', page: 2 })

    expect(Object.fromEntries(new URL(calls[0]!.url).searchParams)).toEqual({ status: 'OPEN', tag: 'spam', page: '2' })
  })
})

describe('case writes', () => {
  it('sends the version with a change and leaves the assignee alone when absent', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.updateCase('p-1', { expectedVersion: 3, status: 'WAITING' })

    expect(calls[0]!.method).toBe('PATCH')
    expect(JSON.parse(calls[0]!.body)).toEqual({ expectedVersion: 3, status: 'WAITING', clearAssignee: false })
  })

  it('turns a null assignee into the clearAssignee flag', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.updateCase('p-1', { expectedVersion: 3, assigneeOid: null })

    expect(JSON.parse(calls[0]!.body)).toEqual({ expectedVersion: 3, clearAssignee: true })
  })

  it('reports a stale version as a conflict', async () => {
    const { api: client } = api([{ status: 409, body: { errorMessage: 'the case was changed by someone else', kind: 'CONFLICT' } }])

    const result = await client.updateCase('p-1', { expectedVersion: 1, status: 'CLOSED' })

    expect(result).toEqual({ ok: false, error: { status: 409, message: 'the case was changed by someone else', kind: 'CONFLICT' } })
  })

  it('posts, edits and redacts notes on their own routes', async () => {
    const { calls, api: client } = api([{ body: { noteId: NOTE, seq: 1 } }, { body: { noteId: NOTE, seq: 2 } }, { body: { noteId: NOTE, redactedVersions: 2 } }])

    await client.addCaseNote('p-1', 'first')
    await client.editCaseNote('p-1', NOTE, 'second')
    await client.redactCaseNote('p-1', NOTE, 'PERSONAL_DATA')

    expect(calls.map((call) => `${call.method} ${new URL(call.url).pathname}`)).toEqual([
      'POST /api/case/p-1/notes',
      `PUT /api/case/p-1/notes/${NOTE}`,
      `POST /api/case/p-1/notes/${NOTE}/redact`,
    ])
    expect(JSON.parse(calls[2]!.body)).toEqual({ reason: 'PERSONAL_DATA' })
  })

  it('hands the server message of a rejected note to the caller', async () => {
    const { api: client } = api([{ status: 400, body: { errorMessage: 'a note must not contain a UUID or an IP address', kind: 'VALIDATION' } }])

    const result = await client.addCaseNote('p-1', '10.0.0.1')

    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error.message).toBe('a note must not contain a UUID or an IP address')
  })
})

describe('active punishments', () => {
  it('asks /punishment/active with only the filters that are set', async () => {
    const { calls, api: client } = api([{ body: { items: [] } }])

    await client.activePunishments({ kind: 'BAN', permanent: false, expiresBefore: 1700, sort: 'EXPIRES', direction: 'ASC', page: 1 })

    const url = new URL(calls[0]!.url)
    expect(url.pathname).toBe('/api/punishment/active')
    expect(Object.fromEntries(url.searchParams)).toEqual({ kind: 'BAN', permanent: 'false', expiresBefore: '1700', sort: 'EXPIRES', direction: 'ASC', page: '1' })
  })
})
