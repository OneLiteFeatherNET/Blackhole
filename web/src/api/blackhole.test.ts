import { describe, expect, it } from 'vitest'
import { createBlackholeApi } from './blackhole'
import { DEFAULT_EVIDENCE_LIMITS, loadConfig } from './config'
import { fakeFetch } from '../test/fakeFetch'
import { fakeHttpAuth } from '../test/auth'


describe('createBlackholeApi', () => {
  it('sends the API version header on every request', async () => {
    const { calls, impl } = fakeFetch([{ body: { latestEscalations: [], activePunishmentsBySection: [] } }])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl })

    await api.dashboard()

    expect(calls).toHaveLength(1)
    expect(calls[0]!.headers.get('X-API-VERSION'), 'version header').toBe('1')
    expect(calls[0]!.url).toBe('http://ui.test/api/dashboard/summary')
  })

  it('reads the inbox from GET /inbox with the four sections', async () => {
    const { calls, impl } = fakeFetch([{ body: { generatedAt: 1, needsAnswer: { count: 0, items: [] }, openAppeals: { count: 0, items: [] }, newReports: { count: 0, items: [] }, unassignedCases: { count: 0, items: [] } } }])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl })

    const result = await api.inbox()

    expect(calls[0]!.url).toBe('http://ui.test/api/inbox')
    expect(result.ok && result.data.openAppeals.count).toBe(0)
  })

  it('returns the data of a successful call', async () => {
    const summary = { activePunishmentsBySection: [{ section: 'chat', active: 2 }], latestEscalations: [], openReports: 4 }
    const { impl } = fakeFetch([{ body: summary }])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl })

    const result = await api.dashboard()

    expect(result).toEqual({ ok: true, data: summary })
  })

  it('maps the backend error DTO to an ApiError with its kind', async () => {
    const { impl } = fakeFetch([{ status: 409, body: { errorMessage: 'section is disabled', kind: 'CONFLICT' } }])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl })

    const result = await api.dashboard()

    expect(result).toEqual({ ok: false, error: { status: 409, message: 'section is disabled', kind: 'CONFLICT' } })
  })

  it('reads the message of a Micronaut validation error body', async () => {
    const { impl } = fakeFetch([
      { status: 400, body: { message: 'Bad Request', _embedded: { errors: [{ message: 'template.key: must not be blank' }] } } },
    ])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl })

    const result = await api.dashboard()

    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error.message).toBe('template.key: must not be blank')
  })

  it('reports an unreachable backend as a NETWORK error instead of throwing', async () => {
    const { impl } = fakeFetch([new TypeError('Failed to fetch')])
    const api = createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl })

    const result = await api.dashboard()

    expect(result).toEqual({ ok: false, error: { status: 0, message: 'Failed to fetch', kind: 'NETWORK' } })
  })
})

describe('loadConfig', () => {
  const OIDC = { authority: 'https://login.example.org/tenant/v2.0', clientId: 'spa-client', apiScope: 'api://x/access_as_user' }

  it('reads the sign-in settings from config.json', async () => {
    const { impl } = fakeFetch([{ body: { oidc: OIDC } }])

    expect(await loadConfig(impl)).toEqual({ oidc: OIDC, evidence: DEFAULT_EVIDENCE_LIMITS })
  })

  it('has no sign-in settings when config.json is missing', async () => {
    const { impl } = fakeFetch([{ status: 404 }])

    expect(await loadConfig(impl)).toEqual({ oidc: null, evidence: DEFAULT_EVIDENCE_LIMITS })
  })

  it.each([{}, { authority: 'https://x' }, { ...OIDC, clientId: ' ' }, { ...OIDC, apiScope: 3 }, 'oidc'])(
    'has no sign-in settings when the oidc section is %j',
    async (oidc) => {
      const { impl } = fakeFetch([{ body: { oidc } }])

      expect((await loadConfig(impl)).oidc).toBeNull()
    },
  )

  it('has no sign-in settings when the request fails', async () => {
    const { impl } = fakeFetch([new TypeError('offline')])

    expect(await loadConfig(impl)).toEqual({ oidc: null, evidence: DEFAULT_EVIDENCE_LIMITS })
  })

  it('reads the evidence upload limits from config.json', async () => {
    const evidence = { maxImageBytes: 1000, maxVideoBytes: 2000, allowedTypes: ['image/png'] }
    const { impl } = fakeFetch([{ body: { oidc: OIDC, evidence } }])

    expect((await loadConfig(impl)).evidence).toEqual(evidence)
  })

  it('falls back to the backend default for each invalid evidence limit', async () => {
    const evidence = { maxImageBytes: -5, maxVideoBytes: 2000, allowedTypes: [] }
    const { impl } = fakeFetch([{ body: { oidc: OIDC, evidence } }])

    expect((await loadConfig(impl)).evidence).toEqual({ ...DEFAULT_EVIDENCE_LIMITS, maxVideoBytes: 2000 })
  })
})
