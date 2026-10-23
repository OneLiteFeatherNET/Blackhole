import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'
import type { SectionDraft } from './types'

const draft: SectionDraft = {
  key: 'games',
  displayName: 'Games',
  enforcement: 'SERVER',
  targetKind: 'CLOUDNET_GROUP',
  servers: [' Games ', ''],
  baseScore: 1000,
  softThreshold: null,
  softDuration: null,
  permanentThreshold: 300,
  pardonScore: 700,
  permanentReason: 'x',
  decayPerDay: 10,
  enabled: true,
}

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

describe('cloudnetCatalogue', () => {
  it('reads the merged catalogue with a plain GET', async () => {
    const body = { available: true, reportedAt: '2026-10-23T12:00:00Z', tasks: [{ name: 'BedWars' }], groups: ['Games'], services: [], proxies: ['Proxy-1'] }
    const { calls, api: client } = api([{ body }])

    const result = await client.cloudnetCatalogue()

    expect(calls).toHaveLength(1)
    expect(calls[0]!.method).toBe('GET')
    expect(calls[0]!.url).toBe('http://ui.test/api/cloudnet/catalogue')
    expect(result).toEqual({ ok: true, data: body })
  })

  it('turns lists the backend leaves out into empty lists', async () => {
    const { api: client } = api([{ body: { available: false } }])

    const result = await client.cloudnetCatalogue()

    expect(result).toEqual({ ok: true, data: { available: false, tasks: [], groups: [], services: [], proxies: [] } })
  })

  it('reports a failing request as an error instead of throwing', async () => {
    const { api: client } = api([{ status: 503, body: { errorMessage: 'cache down', kind: 'UNKNOWN' } }])

    const result = await client.cloudnetCatalogue()

    expect(result.ok).toBe(false)
  })
})

describe('section target kind on the wire', () => {
  it('sends the kind and the trimmed names of a server section', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.createSection(draft)

    const body = JSON.parse(calls[0]!.body)
    expect(body.targetKind).toBe('CLOUDNET_GROUP')
    expect(body.servers).toEqual(['Games'])
  })

  it('saves a section that is not a server section as PATTERN without names', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.createSection({ ...draft, enforcement: 'BAN' })

    const body = JSON.parse(calls[0]!.body)
    expect(body.targetKind).toBe('PATTERN')
    expect(body.servers).toEqual([])
  })

  it('sends the kind on update as well', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.updateSection({ ...draft, targetKind: 'CLOUDNET_SERVICE' })

    expect(JSON.parse(calls[0]!.body).targetKind).toBe('CLOUDNET_SERVICE')
  })
})
