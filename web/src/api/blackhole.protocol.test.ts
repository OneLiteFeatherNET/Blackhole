import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const EVIDENCE = '3f2b8c1e-0000-4000-8000-0000000000e1'
const route = (call: { method: string; url: string }) => `${call.method} ${new URL(call.url).pathname}`

describe('stored appeal protocols', () => {
  it('lists the readable versions of the case', async () => {
    const version = { appealId: EVIDENCE, version: 2, evidenceId: EVIDENCE, current: true, entryCount: 4, sourceDigest: 'abc', createdAt: 1 }
    const { calls, api: client } = api([{ body: { items: [version] } }])

    const result = await client.caseProtocols('p-1')

    expect(route(calls[0]!)).toBe('GET /api/case/p-1/protocol')
    expect(result).toEqual({ ok: true, data: [version] })
  })

  it('reads the ids of the decided appeals from the timeline', async () => {
    const item = (appealId: string) => ({ seq: 1, identifier: EVIDENCE, type: 'appeal.decided', payloadVersion: 1, occurredAt: 1, origin: 'LIVE', actor: { trust: 'SYSTEM' }, payload: { appealId } })
    const { calls, api: client } = api([{ body: { items: [item('a-1'), item('a-2')], nextCursor: null } }])

    const result = await client.caseDecidedAppeals('p-1')

    expect(new URL(calls[0]!.url).searchParams.getAll('type')).toEqual(['appeal.decided'])
    expect(result).toEqual({ ok: true, data: ['a-1', 'a-2'] })
  })

  it('opens the text of one version by its evidence id', async () => {
    const text = { appealId: EVIDENCE, version: 2, evidenceId: EVIDENCE, text: 'Player: hello' }
    const { calls, api: client } = api([{ body: text }])

    const result = await client.openCaseProtocol('p-1', EVIDENCE)

    expect(route(calls[0]!)).toBe(`GET /api/case/p-1/protocol/${EVIDENCE}`)
    expect(result).toEqual({ ok: true, data: text })
  })

  it.each([
    ['text', 'text/plain'],
    ['json', 'application/json'],
  ] as const)('downloads a version as %s', async (format, mediaType) => {
    const { calls, api: client } = api([{ body: 'file content' }])

    const result = await client.downloadCaseProtocol('p-1', EVIDENCE, format)

    expect(route(calls[0]!)).toBe(`GET /api/case/p-1/protocol/${EVIDENCE}/download`)
    expect(new URL(calls[0]!.url).searchParams.get('format')).toBe(format)
    expect(result.ok && result.data.mediaType).toBe(mediaType)
  })

  it('reports an unreadable version with the status of the server', async () => {
    const { api: client } = api([{ status: 404, body: { errorMessage: 'Unknown protocol', kind: 'NOT_FOUND' } }])

    const result = await client.openCaseProtocol('p-1', EVIDENCE)

    expect(result.ok).toBe(false)
    expect(!result.ok && result.error.status).toBe(404)
  })
})
