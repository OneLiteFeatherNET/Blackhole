import { describe, expect, it } from 'vitest'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi, pageContent } from './blackhole'

const SOURCE = '11111111-2222-3333-4444-555555555555'
const OWNER = 'ab'.repeat(64)

function apiWith(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', source: SOURCE, fetch: impl }) }
}

describe('resolvePlayer', () => {
  it('returns the player with its owner hash', async () => {
    const player = { uuid: SOURCE, name: 'Steve', source: 'otis', owner: OWNER }
    const { api, calls } = apiWith([{ body: player }])

    const result = await api.resolvePlayer('Steve')

    expect(result).toEqual({ ok: true, data: player })
    expect(calls[0]!.url).toBe('http://ui.test/api/player/resolve/Steve')
  })

  it('fails when the backend answers without an owner hash', async () => {
    const { api } = apiWith([{ body: { uuid: SOURCE, name: 'Steve', source: 'otis' } }])

    const result = await api.resolvePlayer('Steve')

    expect(result.ok, 'a lookup without owner is unusable').toBe(false)
  })

  it('reports an unknown name as NOT_FOUND with the backend message', async () => {
    const { api } = apiWith([{ status: 404, body: { errorMessage: 'No resolver found a player named Nobody' } }])

    const result = await api.resolvePlayer('Nobody')

    expect(result).toEqual({
      ok: false,
      error: { status: 404, message: 'No resolver found a player named Nobody', kind: 'NOT_FOUND' },
    })
  })
})

describe('profile', () => {
  it('treats a missing profile as a player without punishments', async () => {
    const { api } = apiWith([{ status: 404, body: { 'The error message': 'no profile' } }])

    expect(await api.profile(OWNER)).toEqual({ ok: true, data: { active: {}, history: [] } })
  })

  it('fills in the collections the backend omits when they are empty', async () => {
    const { api } = apiWith([{ body: { owner: OWNER, metaData: {} } }])

    expect(await api.profile(OWNER)).toEqual({ ok: true, data: { active: {}, history: [] } })
  })

  it('keeps other failures as errors', async () => {
    const { api } = apiWith([{ status: 500, body: { message: 'boom' } }])

    const result = await api.profile(OWNER)

    expect(result.ok).toBe(false)
  })
})

describe('eloHistory', () => {
  const event = (createdAt: number) => ({ identifier: `e-${createdAt}`, delta: -10, createdAt })

  it('requests pages until the last one and sorts newest first', async () => {
    const { api, calls } = apiWith([
      { body: { content: [event(1), event(3)], totalPages: 2 } },
      { body: { content: [event(2)], totalPages: 2 } },
    ])

    const result = await api.eloHistory(OWNER, 'network')

    expect(result.ok && result.data.map((e) => e.createdAt)).toEqual([3, 2, 1])
    expect(calls.map((c) => new URL(c.url).search)).toEqual([
      '?section=network&page=0&size=200',
      '?section=network&page=1&size=200',
    ])
  })

  it('stops with the error of a failing page', async () => {
    const { api } = apiWith([{ body: { content: [event(1)], totalPages: 2 } }, { status: 500, body: {} }])

    const result = await api.eloHistory(OWNER, 'network')

    expect(result.ok).toBe(false)
  })
})

describe('pageContent', () => {
  it('reads Micronaut pages, plain arrays and unknown bodies', () => {
    expect(pageContent({ content: [1, 2], totalPages: 3 })).toEqual({ items: [1, 2], totalPages: 3 })
    expect(pageContent([1])).toEqual({ items: [1], totalPages: 1 })
    expect(pageContent(null)).toEqual({ items: [], totalPages: 1 })
  })
})
