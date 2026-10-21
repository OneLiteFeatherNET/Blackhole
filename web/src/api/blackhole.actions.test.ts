import { describe, expect, it } from 'vitest'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi, MAX_NOTE_LENGTH, withActor } from './blackhole'

const SOURCE = '11111111-2222-3333-4444-555555555555'
const OWNER = 'ab'.repeat(64)

function apiWith(responses: Parameters<typeof fakeFetch>[0], actingAs = () => '') {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', source: SOURCE, actingAs, fetch: impl }) }
}

describe('staff actions', () => {
  it('applies a template as the configured operator', async () => {
    const { api, calls } = apiWith([{ body: { step: 1, stepCount: 3 } }])

    await api.applyTemplate(OWNER, 'hacking')

    expect(calls[0]!.method).toBe('POST')
    expect(new URL(calls[0]!.url).pathname).toBe('/api/punishment/apply')
    expect(JSON.parse(calls[0]!.body)).toEqual({ owner: OWNER, templateKey: 'hacking', source: SOURCE })
  })

  it('previews through its own endpoint so nothing is applied', async () => {
    const preview = { step: 2, stepCount: 3, scoreAfter: 750, escalatesToPermanent: false }
    const { api, calls } = apiWith([{ body: preview }])

    const result = await api.previewApply(OWNER, 'hacking')

    expect(result).toEqual({ ok: true, data: preview })
    expect(new URL(calls[0]!.url).pathname).toBe('/api/punishment/preview')
  })

  it('revokes the active punishment of a section', async () => {
    const { api, calls } = apiWith([{ body: { section: 'network', scoreReset: 700 } }])

    const result = await api.revoke(OWNER, 'network')

    expect(result.ok && result.data.scoreReset).toBe(700)
    expect(JSON.parse(calls[0]!.body)).toEqual({ owner: OWNER, section: 'network', source: SOURCE })
  })

  it('surfaces the conflict of a permanently punished player', async () => {
    const { api } = apiWith([{ status: 409, body: { errorMessage: 'already permanently punished', kind: 'CONFLICT' } }])

    const result = await api.applyTemplate(OWNER, 'hacking')

    expect(result).toEqual({ ok: false, error: { status: 409, message: 'already permanently punished', kind: 'CONFLICT' } })
  })

  it('adjusts a score with the note prefixed by the acting-as label', async () => {
    const { api, calls } = apiWith([{ body: { section: 'chat', scoreBefore: 900, scoreAfter: 850 } }], () => ' Alex ')

    await api.adjustElo(OWNER, { section: 'chat', delta: -50, note: 'spam wave' })

    expect(new URL(calls[0]!.url).pathname).toBe(`/api/elo/${OWNER}/adjust`)
    expect(JSON.parse(calls[0]!.body)).toEqual({ section: 'chat', delta: -50, note: '[Alex] spam wave', source: SOURCE })
  })

  it('sends the note unchanged without an acting-as label', async () => {
    const { api, calls } = apiWith([{ body: {} }])

    await api.adjustElo(OWNER, { section: 'chat', delta: 10, note: 'goodwill' })

    expect(JSON.parse(calls[0]!.body).note).toBe('goodwill')
  })
})

describe('withActor', () => {
  it('truncates to the backend limit', () => {
    expect(withActor('x'.repeat(600), 'Alex')).toHaveLength(MAX_NOTE_LENGTH)
  })
})

describe('templates', () => {
  it('collects every page', async () => {
    const { api, calls } = apiWith([
      { body: { content: [{ key: 'a' }], totalPages: 2 } },
      { body: { content: [{ key: 'b' }], totalPages: 2 } },
    ])

    const result = await api.templates()

    expect(result.ok && result.data.map((t) => t.key)).toEqual(['a', 'b'])
    expect(calls.map((c) => new URL(c.url).search)).toEqual(['?page=0&size=200', '?page=1&size=200'])
  })
})
