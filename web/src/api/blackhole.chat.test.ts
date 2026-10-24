import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const ATTACHMENT = '6a1f0c3e-0000-4000-8000-000000000031'
const SHARE = '6a1f0c3e-0000-4000-8000-000000000041'
const EVIDENCE = '6a1f0c3e-0000-4000-8000-000000000051'
const route = (call: { method: string; url: string }) => `${call.method} ${new URL(call.url).pathname}`

function share(overrides: Record<string, unknown> = {}) {
  return { shareId: SHARE, evidenceId: EVIDENCE, evidenceKind: 'CHAT_LOG', mode: 'EXCERPT', state: 'ACTIVE', sharedAt: 1, sharedBy: '00000000-0000-4000-8000-0000000000a1', repeated: false, lineCount: 2, ...overrides }
}

describe('chat attachments and message files', () => {
  it('uploads one staged file as multipart form data', async () => {
    const { calls, api: client } = api([{ body: { attachmentId: ATTACHMENT, name: 'shot.png', contentType: 'image/png', size: 4 } }])

    const result = await client.uploadCaseAttachment('p-1', new File(['pixels'], 'shot.png', { type: 'image/png' }))

    expect(route(calls[0]!)).toBe('POST /api/case/p-1/attachments')
    expect(calls[0]!.headers.get('Content-Type')).toMatch(/^multipart\/form-data; boundary=/)
    // The part is named `file`; jsdom's File does not carry its bytes into undici's FormData, so the bytes are not checked here.
    expect(calls[0]!.body).toContain('name="file"')
    expect(result).toEqual({ ok: true, data: { attachmentId: ATTACHMENT, name: 'shot.png', contentType: 'image/png', size: 4 } })
  })

  it('posts a message with the staged attachment ids, and the text may be empty', async () => {
    const { calls, api: client } = api([{ body: { messageId: 'm-1', seq: 4, repeated: false } }])

    await client.postCaseMessage('p-1', '', 'key-2', [ATTACHMENT])

    expect(JSON.parse(calls[0]!.body)).toEqual({ text: '', attachmentIds: [ATTACHMENT] })
    expect(calls[0]!.headers.get('Idempotency-Key')).toBe('key-2')
  })

  it('leaves attachmentIds out of a plain message', async () => {
    const { calls, api: client } = api([{ body: { messageId: 'm-1', seq: 4, repeated: false } }])

    await client.postCaseMessage('p-1', 'Hello')

    expect(JSON.parse(calls[0]!.body)).toEqual({ text: 'Hello' })
  })
})

describe('evidence counts', () => {
  it('returns the team count and the player-supplied count separately', async () => {
    const { calls, api: client } = api([{ body: { count: 3, playerSupplied: 2 } }])

    const result = await client.evidenceCounts('p-1')

    expect(route(calls[0]!)).toBe('GET /api/punishment/p-1/evidence/count')
    expect(result).toEqual({ ok: true, data: { count: 3, playerSupplied: 2 } })
  })
})

describe('evidence shares', () => {
  it('lists the shares of a case as items', async () => {
    const { calls, api: client } = api([{ body: { items: [share()] } }])

    const result = await client.evidenceShares('p-1')

    expect(route(calls[0]!)).toBe('GET /api/case/p-1/evidence-shares')
    expect(result).toEqual({ ok: true, data: [share()] })
  })

  it('previews a selection without changing anything', async () => {
    const preview = { mode: 'EXCERPT', lines: [{ speaker: 'You', offsetSeconds: 0, text: 'hi' }] }
    const { calls, api: client } = api([{ body: preview }])

    const result = await client.previewEvidenceShare('p-1', { evidenceId: EVIDENCE, selection: [0, 1], appellantSpeaker: 'Steve' })

    expect(route(calls[0]!)).toBe('POST /api/case/p-1/evidence-shares/preview')
    expect(JSON.parse(calls[0]!.body)).toEqual({ evidenceId: EVIDENCE, selection: [0, 1], appellantSpeaker: 'Steve' })
    expect(result).toEqual({ ok: true, data: preview })
  })

  it('shares with the confirmation flag in the body', async () => {
    const { calls, api: client } = api([{ body: share() }])

    await client.shareEvidence('p-1', { evidenceId: EVIDENCE, selection: [0, 1], appellantSpeaker: 'Steve', confirmedMinimised: true })

    expect(route(calls[0]!)).toBe('POST /api/case/p-1/evidence-shares')
    expect(JSON.parse(calls[0]!.body)).toEqual({ evidenceId: EVIDENCE, selection: [0, 1], appellantSpeaker: 'Steve', confirmedMinimised: true })
  })

  it('revokes a share with a reason code on its own route', async () => {
    const { calls, api: client } = api([{ body: share({ state: 'REVOKED', revokedAt: 2, revokeReason: 'PRIVACY' }) }])

    const result = await client.revokeEvidenceShare('p-1', SHARE, 'PRIVACY')

    expect(route(calls[0]!)).toBe(`POST /api/case/p-1/evidence-shares/${SHARE}/revoke`)
    expect(JSON.parse(calls[0]!.body)).toEqual({ reason: 'PRIVACY' })
    expect(result.ok && result.data.state).toBe('REVOKED')
  })

  it('reports a refused share with its code', async () => {
    const { api: client } = api([{ status: 400, body: { errorMessage: 'The share needs a confirmation', kind: 'VALIDATION', code: 'NOT_CONFIRMED' } }])

    const result = await client.shareEvidence('p-1', { evidenceId: EVIDENCE, selection: [0], confirmedMinimised: false })

    expect(result).toEqual({ ok: false, error: { status: 400, message: 'The share needs a confirmation', kind: 'VALIDATION' } })
  })
})
