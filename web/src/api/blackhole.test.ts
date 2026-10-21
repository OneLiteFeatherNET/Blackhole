import { describe, expect, it } from 'vitest'
import { createBlackholeApi } from './blackhole'
import { loadConfig, NIL_UUID } from './config'

const SOURCE = '11111111-2222-3333-4444-555555555555'

interface Recorded {
  url: string
  method: string
  headers: Headers
  body: string
}

/** A fetch that records requests and answers from a queue; no network, no timers. */
function fakeFetch(responses: Array<{ status?: number; body?: unknown } | Error>) {
  const calls: Recorded[] = []
  const impl = async (input: Request | string | URL, init?: RequestInit): Promise<Response> => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://ui.test'), init)
    calls.push({
      url: request.url,
      method: request.method,
      headers: request.headers,
      body: await request.clone().text(),
    })
    const next = responses.shift()
    if (next instanceof Error) throw next
    return new Response(next?.body === undefined ? null : JSON.stringify(next.body), {
      status: next?.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  return { calls, impl: impl as unknown as typeof fetch }
}

describe('createBlackholeApi', () => {
  it('sends the API version header on every request', async () => {
    const { calls, impl } = fakeFetch([{ body: { latestEscalations: [], activePunishmentsBySection: [] } }])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', source: SOURCE, fetch: impl })

    await api.dashboard()

    expect(calls).toHaveLength(1)
    expect(calls[0]!.headers.get('X-API-VERSION'), 'version header').toBe('1')
    expect(calls[0]!.url).toBe('http://ui.test/api/dashboard/summary')
  })

  it('returns the data of a successful call', async () => {
    const summary = { activePunishmentsBySection: [{ section: 'chat', active: 2 }], latestEscalations: [], openReports: 4 }
    const { impl } = fakeFetch([{ body: summary }])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', source: SOURCE, fetch: impl })

    const result = await api.dashboard()

    expect(result).toEqual({ ok: true, data: summary })
  })

  it('maps the backend error DTO to an ApiError with its kind', async () => {
    const { impl } = fakeFetch([{ status: 409, body: { errorMessage: 'section is disabled', kind: 'CONFLICT' } }])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', source: SOURCE, fetch: impl })

    const result = await api.dashboard()

    expect(result).toEqual({ ok: false, error: { status: 409, message: 'section is disabled', kind: 'CONFLICT' } })
  })

  it('reads the message of a Micronaut validation error body', async () => {
    const { impl } = fakeFetch([
      { status: 400, body: { message: 'Bad Request', _embedded: { errors: [{ message: 'template.key: must not be blank' }] } } },
    ])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', source: SOURCE, fetch: impl })

    const result = await api.dashboard()

    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error.message).toBe('template.key: must not be blank')
  })

  it('reports an unreachable backend as a NETWORK error instead of throwing', async () => {
    const { impl } = fakeFetch([new TypeError('Failed to fetch')])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', source: SOURCE, fetch: impl })

    const result = await api.dashboard()

    expect(result).toEqual({ ok: false, error: { status: 0, message: 'Failed to fetch', kind: 'NETWORK' } })
  })
})

describe('loadConfig', () => {
  it('reads the operator UUID from config.json', async () => {
    const { impl } = fakeFetch([{ body: { source: SOURCE } }])

    expect(await loadConfig(impl)).toEqual({ source: SOURCE })
  })

  it('falls back to the nil UUID when config.json is missing', async () => {
    const { impl } = fakeFetch([{ status: 404 }])

    expect(await loadConfig(impl)).toEqual({ source: NIL_UUID })
  })

  it('falls back when config.json holds an invalid UUID', async () => {
    const { impl } = fakeFetch([{ body: { source: 'not-a-uuid' } }])

    expect(await loadConfig(impl)).toEqual({ source: NIL_UUID })
  })

  it('falls back when the request fails', async () => {
    const { impl } = fakeFetch([new TypeError('offline')])

    expect(await loadConfig(impl)).toEqual({ source: NIL_UUID })
  })
})
