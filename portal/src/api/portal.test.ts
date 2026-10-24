import { describe, expect, it } from 'vitest'
import { createPortalApi } from './portal'

function client(reply: () => Response | Promise<Response>, csrf: string | null = 'csrf-1') {
  const calls: Array<{ path: string; init: RequestInit }> = []
  const api = createPortalApi({
    fetch: (async (path: string, init: RequestInit) => {
      calls.push({ path, init })
      return reply()
    }) as unknown as typeof fetch,
    csrf: () => csrf,
  })
  return { api, calls }
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

describe('portal api client', () => {
  it('sends the Ban ID in the body of a POST and nowhere else', async () => {
    const { api, calls } = client(() => json({ csrf: 'c', expiresAt: 'x', scope: 'FULL' }))

    await api.login('K7M29QX4')

    expect(calls[0]?.path).toBe('/api/session')
    expect(calls[0]?.init.method).toBe('POST')
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ banId: 'K7M29QX4' })
    expect(JSON.stringify(calls[0]?.init.headers)).not.toContain('K7M29QX4')
  })

  it('adds the CSRF header to state-changing calls only', async () => {
    const { api, calls } = client(() => json({}))

    await api.punishment()
    await api.postMessage('hi', 'key-123456')
    await api.logout()

    expect((calls[0]?.init.headers as Record<string, string>)['x-csrf-token']).toBeUndefined()
    expect((calls[1]?.init.headers as Record<string, string>)['x-csrf-token']).toBe('csrf-1')
    expect((calls[2]?.init.headers as Record<string, string>)['x-csrf-token']).toBe('csrf-1')
  })

  it('sends the idempotency key and uses same-origin credentials without caching', async () => {
    const { api, calls } = client(() => json({}))

    await api.postMessage('hi', 'key-123456')

    expect((calls[0]?.init.headers as Record<string, string>)['idempotency-key']).toBe('key-123456')
    expect(calls[0]?.init.credentials).toBe('same-origin')
    expect(calls[0]?.init.cache).toBe('no-store')
  })

  it('puts the cursor in the query and nothing else', async () => {
    const { api, calls } = client(() => json({ entries: [], cursor: null }))

    await api.conversation(null)
    await api.conversation(42)

    expect(calls.map((c) => c.path)).toEqual(['/api/conversation', '/api/conversation?cursor=42'])
  })

  it('treats 204 as success without a body', async () => {
    const { api } = client(() => new Response(null, { status: 204 }))

    expect(await api.logout()).toEqual({ ok: true, data: null })
  })

  it('reads the fixed error code and the date of a conflict', async () => {
    const { api } = client(() => json({ code: 'NOT_ELIGIBLE', availableFrom: '2026-11-01T00:00:00Z' }, 409))

    expect(await api.fileAppeal('x', 1)).toEqual({ ok: false, error: { status: 409, code: 'NOT_ELIGIBLE', availableFrom: '2026-11-01T00:00:00Z' } })
  })

  it('maps an answer without a usable body to unavailable', async () => {
    const { api } = client(() => new Response('<html>bad gateway</html>', { status: 502 }))

    expect(await api.punishment()).toEqual({ ok: false, error: { status: 502, code: 'UNAVAILABLE' } })
  })

  it('maps a network failure to unavailable', async () => {
    const { api } = client(() => Promise.reject(new TypeError('network')))

    expect(await api.punishment()).toEqual({ ok: false, error: { status: 0, code: 'UNAVAILABLE' } })
  })

  it('sends the confirmed conditions version with the statement', async () => {
    const { api, calls } = client(() => json({ status: 'ELIGIBLE_PENDING_REVIEW', decision: null, newExpiresAt: null, filedAt: null }))

    await api.fileAppeal('my statement', 4)

    expect(calls[0]?.path).toBe('/api/appeal')
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ statement: 'my statement', conditionsVersion: 4 })
  })

  it('reads the conditions with a GET and no CSRF header', async () => {
    const { api, calls } = client(() => json({ version: 2, text: 'rules' }))

    const result = await api.appealConditions()

    expect(result).toEqual({ ok: true, data: { version: 2, text: 'rules' } })
    expect([calls[0]?.path, calls[0]?.init.method]).toEqual(['/api/appeal/conditions', 'GET'])
    expect((calls[0]?.init.headers as Record<string, string>)['x-csrf-token']).toBeUndefined()
  })

  it('reads CONDITIONS_CHANGED as the fixed error code', async () => {
    const { api } = client(() => json({ code: 'CONDITIONS_CHANGED' }, 409))

    expect(await api.fileAppeal('x', 1)).toEqual({ ok: false, error: { status: 409, code: 'CONDITIONS_CHANGED' } })
  })
})
