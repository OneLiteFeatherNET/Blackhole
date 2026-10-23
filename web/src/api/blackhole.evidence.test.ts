import { describe, expect, it } from 'vitest'
import { fakeFetch } from '../test/fakeFetch'
import { fakeHttpAuth } from '../test/auth'
import { createBlackholeApi } from './blackhole'

const PUNISHMENT = 'ab0e5a9e-1111-4222-8333-444455556666'
const ITEM = 'cd0e5a9e-1111-4222-8333-444455556666'

function apiWith(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

describe('evidence calls', () => {
  it('lists the evidence of a punishment without includeInactive by default', async () => {
    const page = { items: [], page: 0, size: 20, totalSize: 0, totalPages: 0 }
    const { api, calls } = apiWith([{ body: page }])

    const result = await api.evidence(PUNISHMENT)

    const url = new URL(calls[0]!.url)
    expect(result).toEqual({ ok: true, data: page })
    expect(calls[0]!.method).toBe('GET')
    expect(url.pathname).toBe(`/api/punishment/${PUNISHMENT}/evidence`)
    expect(url.searchParams.has('includeInactive'), 'includeInactive').toBe(false)
    expect(url.searchParams.has('source'), 'source').toBe(false)
    expect(url.searchParams.get('page')).toBe('0')
    expect(url.searchParams.get('size')).toBe('20')
  })

  it('turns a page without an items array into an empty list', async () => {
    const { api } = apiWith([{ body: { page: 0, size: 20, totalSize: 0, totalPages: 0 } }])

    const result = await api.evidence(PUNISHMENT)

    expect(result.ok && result.data.items).toEqual([])
  })

  it('asks for lifted evidence only when told to', async () => {
    const { api, calls } = apiWith([{ body: { items: [] } }])

    await api.evidence(PUNISHMENT, { includeInactive: true, page: 2, size: 5 })

    const url = new URL(calls[0]!.url)
    expect(url.searchParams.get('includeInactive')).toBe('true')
    expect(url.searchParams.get('page')).toBe('2')
    expect(url.searchParams.get('size')).toBe('5')
  })

  it('encodes a short id in the path', async () => {
    const { api, calls } = apiWith([{ body: { items: [] } }])

    await api.evidence('#A1B2C3D4')

    expect(new URL(calls[0]!.url).pathname).toBe('/api/punishment/%23A1B2C3D4/evidence')
  })

  it('sends the API version header', async () => {
    const { api, calls } = apiWith([{ body: { count: 1 } }])

    await api.evidenceCount(PUNISHMENT)

    expect(calls[0]!.headers.get('X-API-VERSION'), 'version header').toBe('1')
  })

  it('counts valid evidence as a plain number', async () => {
    const { api, calls } = apiWith([{ body: { count: 3 } }])

    const result = await api.evidenceCount(PUNISHMENT)

    expect(result).toEqual({ ok: true, data: 3 })
    expect(new URL(calls[0]!.url).pathname).toBe(`/api/punishment/${PUNISHMENT}/evidence/count`)
  })

  it('reads one item as the operator', async () => {
    const { api, calls } = apiWith([{ body: { identifier: ITEM, kind: 'LINK' } }])

    const result = await api.evidenceItem(ITEM)

    const url = new URL(calls[0]!.url)
    expect(result.ok && result.data.kind).toBe('LINK')
    expect(url.pathname).toBe(`/api/evidence/${ITEM}`)
    expect(url.searchParams.has('source'), 'source').toBe(false)
  })

  it('attaches a link with its description and no client-supplied actor', async () => {
    const { api, calls } = apiWith([{ body: { identifier: ITEM } }])

    await api.addLinkEvidence(PUNISHMENT, { kind: 'REPLAY', url: ' https://replay.example/r/1 ', description: ' the fight ' })

    expect(calls[0]!.method).toBe('POST')
    expect(new URL(calls[0]!.url).pathname).toBe('/api/evidence')
    expect(JSON.parse(calls[0]!.body)).toEqual({
      punishment: PUNISHMENT,
      kind: 'REPLAY',
      sourceSystem: 'admin-ui',
      reference: 'https://replay.example/r/1',
      payload: { description: 'the fight' },
    })
  })

  it('attaches a link without description as a null payload', async () => {
    const { api, calls } = apiWith([{ body: {} }])

    await api.addLinkEvidence(PUNISHMENT, { kind: 'LINK', url: 'https://example.org' })

    expect(JSON.parse(calls[0]!.body).payload).toBeNull()
  })

  it('deletes with the reason and the note in the query', async () => {
    const { api, calls } = apiWith([{ body: { deleted: 1 } }])

    const result = await api.deleteEvidence(ITEM, { reason: 'OTHER', note: ' duplicate ' })

    const url = new URL(calls[0]!.url)
    expect(result).toEqual({ ok: true, data: 1 })
    expect(calls[0]!.method).toBe('DELETE')
    expect(url.pathname).toBe(`/api/evidence/${ITEM}`)
    expect(url.searchParams.get('reason')).toBe('OTHER')
    expect(url.searchParams.get('note')).toBe('duplicate')
    expect(url.searchParams.has('source'), 'source').toBe(false)
  })

  it('leaves the note out of a delete when there is none', async () => {
    const { api, calls } = apiWith([{ body: { deleted: 1 } }])

    await api.deleteEvidence(ITEM, { reason: 'WRONG_EVIDENCE', note: ' ' })

    expect(new URL(calls[0]!.url).searchParams.has('note'), 'note').toBe(false)
  })

  it('surfaces an evidence error with the backend message', async () => {
    const { api } = apiWith([{ status: 404, body: { errorMessage: 'unknown punishment', kind: 'NOT_FOUND' } }])

    const result = await api.evidence(PUNISHMENT)

    expect(result).toEqual({ ok: false, error: { status: 404, message: 'unknown punishment', kind: 'NOT_FOUND' } })
  })

  it('fetches the file content with the bearer token as a blob', async () => {
    const requests: Request[] = []
    const send = (async (request: Request) => {
      requests.push(request)
      return new Response(new Blob(['png-bytes'], { type: 'image/png' }), { headers: { 'Content-Type': 'image/png' } })
    }) as unknown as typeof fetch
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: send })

    const result = await api.evidenceContent(ITEM)

    expect(requests[0]!.url).toBe(`http://ui.test/api/evidence/${ITEM}/content`)
    expect(requests[0]!.headers.get('Authorization'), 'bearer header').toBe('Bearer access-token')
    expect(result.ok && result.data.type, 'content type of the blob').toBe('image/png')
  })
})
