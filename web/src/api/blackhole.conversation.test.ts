import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const MESSAGE = '3f2b8c1e-0000-4000-8000-000000000002'
const route = (call: { method: string; url: string }) => `${call.method} ${new URL(call.url).pathname}`

describe('case messages', () => {
  it('posts a reply with the idempotency key as a header and only the text in the body', async () => {
    const { calls, api: client } = api([{ body: { messageId: MESSAGE, seq: 4, repeated: false } }])

    const result = await client.postCaseMessage('p-1', 'Hello', 'key-1')

    expect(route(calls[0]!)).toBe('POST /api/case/p-1/messages')
    expect(calls[0]!.headers.get('Idempotency-Key')).toBe('key-1')
    expect(JSON.parse(calls[0]!.body)).toEqual({ text: 'Hello' })
    expect(result).toEqual({ ok: true, data: { messageId: MESSAGE, seq: 4, repeated: false } })
  })

  it('sends no idempotency header when none is given', async () => {
    const { calls, api: client } = api([{ body: { messageId: MESSAGE, seq: 4, repeated: false } }])

    await client.postCaseMessage('p-1', 'Hello')

    expect(calls[0]!.headers.has('Idempotency-Key')).toBe(false)
  })

  it('edits, deletes and redacts a message on its own routes', async () => {
    const { calls, api: client } = api([
      { body: { messageId: MESSAGE, seq: 5, repeated: false } },
      { body: { messageId: MESSAGE, seq: 6, repeated: false } },
      { body: { messageId: MESSAGE, redactedVersions: 2 } },
    ])

    await client.editCaseMessage('p-1', MESSAGE, 'Corrected')
    await client.deleteCaseMessage('p-1', MESSAGE)
    await client.redactCaseMessage('p-1', MESSAGE, 'PERSONAL_DATA')

    expect(calls.map(route)).toEqual([
      `PUT /api/case/p-1/messages/${MESSAGE}`,
      `DELETE /api/case/p-1/messages/${MESSAGE}`,
      `POST /api/case/p-1/messages/${MESSAGE}/redact`,
    ])
    expect(JSON.parse(calls[0]!.body)).toEqual({ text: 'Corrected' })
    expect(JSON.parse(calls[2]!.body)).toEqual({ reason: 'PERSONAL_DATA' })
  })

  it('erases the player side with a reason code', async () => {
    const { calls, api: client } = api([{ body: { redactedMessages: 3 } }])

    const result = await client.eraseCasePlayerMessages('p-1', 'ERASURE_REQUEST')

    expect(route(calls[0]!)).toBe('POST /api/case/p-1/transcript/erase-player')
    expect(JSON.parse(calls[0]!.body)).toEqual({ reason: 'ERASURE_REQUEST' })
    expect(result).toEqual({ ok: true, data: { redactedMessages: 3 } })
  })

  it('hands the server message of a rejected reply to the caller', async () => {
    const { api: client } = api([{ status: 400, body: { errorMessage: 'a message must not contain a Ban ID', kind: 'VALIDATION' } }])

    const result = await client.postCaseMessage('p-1', 'ABCD2345')

    expect(result).toEqual({ ok: false, error: { status: 400, message: 'a message must not contain a Ban ID', kind: 'VALIDATION' } })
  })

  it('reports a message that someone else wrote as forbidden', async () => {
    const { api: client } = api([{ status: 403, body: { errorMessage: 'only the author may edit', kind: 'FORBIDDEN' } }])

    const result = await client.editCaseMessage('p-1', MESSAGE, 'x')

    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error.status).toBe(403)
  })
})

describe('Ban ID actions', () => {
  it('marks a leak and reissues with a reason code in the body only', async () => {
    const { calls, api: client } = api([{ body: { changed: true } }, { body: { generation: 2 } }])

    await client.markBanIdLeaked('p-1', 'POSTED_PUBLIC')
    await client.reissueBanId('p-1', 'NOT_PLAYERS_FAULT')

    expect(calls.map(route)).toEqual(['POST /api/case/p-1/ban-id/leak', 'POST /api/case/p-1/ban-id/reissue'])
    expect(JSON.parse(calls[0]!.body)).toEqual({ reason: 'POSTED_PUBLIC' })
    expect(JSON.parse(calls[1]!.body)).toEqual({ reason: 'NOT_PLAYERS_FAULT' })
  })

  it('reveals the Ban ID as a bare string and puts nothing about it in the URL', async () => {
    const { calls, api: client } = api([{ body: { banId: 'K7M2QX9D' } }])

    const result = await client.revealBanId('p-1')

    expect(route(calls[0]!)).toBe('GET /api/punishment/p-1/ban-id')
    expect(calls[0]!.url).not.toContain('K7M2QX9D')
    expect(result).toEqual({ ok: true, data: 'K7M2QX9D' })
  })

  it('reports an unissued Ban ID as not found', async () => {
    const { api: client } = api([{ status: 404, body: { errorMessage: 'no Ban ID yet', kind: 'NOT_FOUND' } }])

    const result = await client.revealBanId('p-1')

    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error.kind).toBe('NOT_FOUND')
  })
})

describe('transcript export', () => {
  it('asks for the format and the notes switch and returns the file text untouched', async () => {
    const { calls, api: client } = api([{ body: { entries: [] } }])

    const result = await client.exportCaseTranscript('p-1', 'json', true)

    const url = new URL(calls[0]!.url)
    expect(url.pathname).toBe('/api/case/p-1/transcript')
    expect(Object.fromEntries(url.searchParams)).toEqual({ format: 'json', includeNotes: 'true' })
    expect(result).toEqual({ ok: true, data: { format: 'json', content: '{"entries":[]}', mediaType: 'application/json' } })
  })

  it('leaves notes out when asked and reports a text file as plain text', async () => {
    const { calls, api: client } = api([{ body: 'plain' }])

    const result = await client.exportCaseTranscript('p-1', 'text', false)

    expect(new URL(calls[0]!.url).searchParams.get('includeNotes')).toBe('false')
    expect(result.ok && result.data.mediaType).toBe('text/plain')
  })
})

describe('appeal review with a player message', () => {
  it('sends the message next to the internal note, and none without one', async () => {
    const { calls, api: client } = api([{ body: {} }, { body: {} }])

    await client.reviewAppeal('a-1', { decision: 'DENIED', note: 'internal', playerMessage: 'We looked again.' })
    await client.reviewAppeal('a-1', { decision: 'DENIED' })

    expect(JSON.parse(calls[0]!.body)).toMatchObject({ decisionNote: 'internal', playerMessage: 'We looked again.' })
    expect(JSON.parse(calls[1]!.body)).toMatchObject({ decisionNote: null })
    expect(JSON.parse(calls[1]!.body)).not.toHaveProperty('playerMessage')
  })
})

describe('case conversation', () => {
  it('asks for the message group and the leak events oldest first and follows the cursor', async () => {
    const first = { seq: 1, type: 'message.added' }
    const second = { seq: 2, type: 'ban_id.leak_marked' }
    const { calls, api: client } = api([{ body: { items: [first], nextCursor: '1700:1' } }, { body: { items: [second], nextCursor: null } }])

    const result = await client.caseConversation('p-1')

    const urls = calls.map((call) => new URL(call.url))
    expect(urls[0]!.pathname).toBe('/api/case/p-1/timeline')
    expect(urls[0]!.searchParams.getAll('type')).toEqual(['message', 'ban_id.leak_marked'])
    expect(urls[0]!.searchParams.get('order')).toBe('asc')
    expect(urls[1]!.searchParams.get('cursor')).toBe('1700:1')
    expect(result.ok && result.data).toEqual([first, second])
  })

  it('stops with the error of a page that fails', async () => {
    const { api: client } = api([{ status: 404, body: { errorMessage: 'Unknown punishment', kind: 'NOT_FOUND' } }])

    const result = await client.caseConversation('nope')

    expect(result.ok).toBe(false)
  })
})
