import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const ID = '3f2b8c1e-0000-4000-8000-000000000001'
const HASH = 'cd'.repeat(64)

function report(identifier: string, createdAt: number) {
  return { identifier, reporterHash: 'ef'.repeat(64), reportedHash: HASH, category: 'CHEATING', createdAt, updatedAt: createdAt, verified: false, metaData: {} }
}

describe('report reads', () => {
  it('reads one report by its identifier', async () => {
    const { calls, api: client } = api([{ body: report(ID, 1) }])

    const result = await client.report(ID)

    expect(calls[0]!.url).toBe(`http://ui.test/api/report/${ID}`)
    expect(calls[0]!.method).toBe('GET')
    expect(result.ok && result.data.identifier).toBe(ID)
  })

  it('maps an unknown report to NOT_FOUND', async () => {
    const { api: client } = api([{ status: 404, body: { errorMessage: 'there is no report with this identifier', kind: 'NOT_FOUND' } }])

    const result = await client.report(ID)

    expect(result).toEqual({ ok: false, error: { status: 404, message: 'there is no report with this identifier', kind: 'NOT_FOUND' } })
  })

  it('asks for the reports against one player only and lists the newest first', async () => {
    const { calls, api: client } = api([{ body: { content: [report('a', 1), report('b', 3), report('c', 2)], totalPages: 1 } }])

    const result = await client.reportsAgainst(HASH)

    const url = new URL(calls[0]!.url)
    expect(url.pathname).toBe('/api/report')
    expect(url.searchParams.get('reportedHash')).toBe(HASH)
    expect(result.ok && result.data.map((entry) => entry.identifier)).toEqual(['b', 'c', 'a'])
  })

  it('resolves a report with the template in the same call', async () => {
    const { calls, api: client } = api([{ body: report(ID, 1) }])

    await client.resolveReport(ID, { status: 'ACTIONED', templateId: 'tpl-1' })

    expect(calls[0]!.url).toBe(`http://ui.test/api/report/${ID}/resolve`)
    expect(JSON.parse(calls[0]!.body)).toEqual({ status: 'ACTIONED', resolutionNote: null, punishmentTemplateId: 'tpl-1' })
  })
})
