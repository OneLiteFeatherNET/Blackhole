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

describe('template management', () => {
  const draft = {
    key: ' hacking ',
    reason: 'Hacking',
    section: 'network',
    escalationGroup: ' ',
    escalationWindow: 'P30D',
    steps: [{ duration: ' P1D ', eloCost: 100 }],
  }

  it('creates a template, trimming text and dropping a blank group', async () => {
    const { api, calls } = apiWith([{ body: { key: 'hacking' } }])

    await api.createTemplate(draft)

    expect(new URL(calls[0]!.url).pathname).toBe('/api/template')
    expect(JSON.parse(calls[0]!.body)).toEqual({
      key: 'hacking',
      reason: 'Hacking',
      section: 'network',
      escalationGroup: null,
      escalationWindow: 'P30D',
      steps: [{ duration: 'P1D', eloCost: 100 }],
    })
  })

  it('updates a template by identifier', async () => {
    const { api, calls } = apiWith([{ body: { key: 'hacking' } }])

    await api.updateTemplate('id-1', draft)

    expect(new URL(calls[0]!.url).pathname).toBe('/api/template/update')
    expect(JSON.parse(calls[0]!.body).identifier).toBe('id-1')
  })

  it('reports the validation error of a step with its message', async () => {
    const { api } = apiWith([
      { status: 400, body: { errorMessage: 'step 1 needs a duration that is a positive ISO-8601 duration', kind: 'VALIDATION' } },
    ])

    const result = await api.createTemplate(draft)

    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error).toMatchObject({ status: 400, kind: 'VALIDATION', message: expect.stringContaining('step 1') })
  })

  it('deletes a template and surfaces the conflict while it is referenced', async () => {
    const { api, calls } = apiWith([
      { status: 409, body: { errorMessage: 'at least one punishment still references this template', kind: 'CONFLICT' } },
    ])

    const result = await api.deleteTemplate('id-1')

    expect(calls[0]!.method).toBe('DELETE')
    expect(new URL(calls[0]!.url).pathname).toBe('/api/template/delete/id-1')
    expect(result).toEqual({
      ok: false,
      error: { status: 409, message: 'at least one punishment still references this template', kind: 'CONFLICT' },
    })
  })
})

describe('section management', () => {
  const draft = {
    key: 'bedwars',
    displayName: ' Bedwars ',
    enforcement: 'SERVER' as const,
    servers: [' bedwars-* ', ''],
    baseScore: 1000,
    softThreshold: null,
    softDuration: 'P3D',
    permanentThreshold: 300,
    pardonScore: 700,
    permanentReason: ' banned ',
    decayPerDay: 10,
    enabled: true,
  }

  it('creates a server section with trimmed patterns', async () => {
    const { api, calls } = apiWith([{ body: { key: 'bedwars' } }])

    await api.createSection(draft)

    expect(new URL(calls[0]!.url).pathname).toBe('/api/section')
    expect(JSON.parse(calls[0]!.body)).toEqual({
      key: 'bedwars',
      displayName: 'Bedwars',
      enforcement: 'SERVER',
      servers: ['bedwars-*'],
      baseScore: 1000,
      softThreshold: null,
      softDuration: null,
      permanentThreshold: 300,
      pardonScore: 700,
      permanentReason: 'banned',
      decayPerDay: 10,
    })
  })

  it('sends no patterns for a section that is not a server section', async () => {
    const { api, calls } = apiWith([{ body: {} }])

    await api.createSection({ ...draft, enforcement: 'BAN', softThreshold: 700 })

    const body = JSON.parse(calls[0]!.body)
    expect(body.servers).toEqual([])
    expect(body.softDuration, 'a soft threshold keeps its duration').toBe('P3D')
  })

  it('updates with the enabled flag', async () => {
    const { api, calls } = apiWith([{ body: {} }])

    await api.updateSection({ ...draft, enabled: false })

    expect(new URL(calls[0]!.url).pathname).toBe('/api/section/update')
    expect(JSON.parse(calls[0]!.body).enabled).toBe(false)
  })

  it('disables a section by key', async () => {
    const { api, calls } = apiWith([{ body: {} }])

    await api.disableSection('chat')

    expect(calls[0]!.method).toBe('POST')
    expect(new URL(calls[0]!.url).pathname).toBe('/api/section/chat/disable')
  })

  it('surfaces a duplicate key as a conflict', async () => {
    const { api } = apiWith([{ status: 409, body: { errorMessage: "a section with key 'chat' already exists", kind: 'CONFLICT' } }])

    const result = await api.createSection(draft)

    expect(result.ok === false && result.error.kind).toBe('CONFLICT')
  })
})

describe('sections', () => {
  it('lists the sections', async () => {
    const { api, calls } = apiWith([{ body: [{ key: 'chat' }, { key: 'network' }] }])

    const result = await api.sections()

    expect(result.ok && result.data.map((s) => s.key)).toEqual(['chat', 'network'])
    expect(new URL(calls[0]!.url).pathname).toBe('/api/section')
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
