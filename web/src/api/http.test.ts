import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function apiWith(responses: Parameters<typeof fakeFetch>[0], authOptions: Parameters<typeof fakeHttpAuth>[0] = {}) {
  const { calls, impl } = fakeFetch(responses)
  const { auth, events } = fakeHttpAuth(authOptions)
  return { calls, events, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth, fetch: impl }) }
}

const SUMMARY = { activePunishmentsBySection: [], latestEscalations: [] }

describe('bearer token', () => {
  it('is sent on every request', async () => {
    const { api, calls } = apiWith([{ body: SUMMARY }])

    await api.dashboard()

    expect(calls[0]!.headers.get('Authorization')).toBe('Bearer access-token')
  })

  it('is sent together with the API version header', async () => {
    const { api, calls } = apiWith([{ body: SUMMARY }])

    await api.dashboard()

    expect(calls[0]!.headers.get('X-API-VERSION')).toBe('1')
  })

  it('is renewed before the first request when there is none', async () => {
    const { api, calls, events } = apiWith([{ body: SUMMARY }], { token: null })

    const result = await api.dashboard()

    expect(result.ok, 'result').toBe(true)
    expect(events.renewals, 'renewals').toBe(1)
    expect(calls, 'requests sent').toHaveLength(1)
    expect(calls[0]!.headers.get('Authorization')).toBe('Bearer renewed-token')
  })
})

describe('401 answer', () => {
  it('renews the token once and repeats the request once with the new token', async () => {
    const { api, calls, events } = apiWith([{ status: 401 }, { body: SUMMARY }])

    const result = await api.dashboard()

    expect(result).toEqual({ ok: true, data: SUMMARY })
    expect(events.renewals, 'renewals').toBe(1)
    expect(calls.map((call) => call.headers.get('Authorization'))).toEqual(['Bearer access-token', 'Bearer renewed-token'])
  })

  it('repeats the body of a write', async () => {
    const { api, calls } = apiWith([{ status: 401 }, { body: {} }])

    await api.applyTemplate('ab'.repeat(64), 'hacking')

    expect(calls, 'requests sent').toHaveLength(2)
    expect(calls[1]!.body).toBe(calls[0]!.body)
    expect(JSON.parse(calls[1]!.body)).toEqual({ owner: 'ab'.repeat(64), templateKey: 'hacking' })
  })

  it('does not retry a second time and signs in again when the renewed token is refused too', async () => {
    const { api, calls, events } = apiWith([{ status: 401 }, { status: 401 }, { body: SUMMARY }])

    const result = await api.dashboard()

    expect(calls, 'requests sent').toHaveLength(2)
    expect(events.signIns, 'sign-ins').toBe(1)
    expect(result.ok === false && result.error.kind).toBe('UNAUTHORIZED')
  })

  it('signs in again without repeating the request when renewal fails', async () => {
    const { api, calls, events } = apiWith([{ status: 401 }, { body: SUMMARY }], { renewed: null })

    const result = await api.dashboard()

    expect(calls, 'requests sent').toHaveLength(1)
    expect(events.signIns, 'sign-ins').toBe(1)
    expect(result.ok === false && result.error.status).toBe(401)
  })

  it('signs in again without any request when there is no token and renewal fails', async () => {
    const { api, calls, events } = apiWith([{ body: SUMMARY }], { token: null, renewed: null })

    const result = await api.dashboard()

    expect(calls, 'requests sent').toHaveLength(0)
    expect(events.signIns, 'sign-ins').toBe(1)
    expect(result.ok === false && result.error.status).toBe(401)
  })
})

describe('403 answer', () => {
  it('is reported, never renewed and never repeated', async () => {
    const { api, calls, events } = apiWith([{ status: 403 }, { body: SUMMARY }])

    const result = await api.dashboard()

    expect(calls, 'requests sent').toHaveLength(1)
    expect(events.renewals, 'renewals').toBe(0)
    expect(events.forbidden, 'forbidden notices').toBe(1)
    expect(result).toEqual({ ok: false, error: { status: 403, message: 'HTTP 403', kind: 'FORBIDDEN' } })
  })

  it('is reported once when it follows a renewed token', async () => {
    const { api, events } = apiWith([{ status: 401 }, { status: 403 }])

    await api.dashboard()

    expect(events.forbidden, 'forbidden notices').toBe(1)
    expect(events.signIns, 'sign-ins').toBe(0)
  })
})

describe('other answers', () => {
  it('a server error is neither renewed nor repeated', async () => {
    const { api, calls, events } = apiWith([{ status: 500 }, { body: SUMMARY }])

    await api.dashboard()

    expect(calls, 'requests sent').toHaveLength(1)
    expect(events.renewals + events.signIns + events.forbidden, 'auth reactions').toBe(0)
  })
})
