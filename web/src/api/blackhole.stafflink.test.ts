import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

const OID = '00000000-0000-4000-8000-0000000000b2'

function apiWith(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const path = (call: { url: string }) => new URL(call.url).pathname

describe('staff link calls', () => {
  it('issues a code with POST /staff-link/code and returns it as the backend formats it', async () => {
    const { api, calls } = apiWith([{ body: { code: 'ABCD-EFGH', expiresAt: 1_000 } }])

    const result = await api.issueStaffLinkCode()

    expect(result, 'the code is passed through unchanged').toEqual({ ok: true, data: { code: 'ABCD-EFGH', expiresAt: 1_000 } })
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['POST', '/api/staff-link/code'])
  })

  it('reads the own link status from GET /staff-link/me', async () => {
    const { api, calls } = apiWith([{ body: { linked: false } }])

    const result = await api.staffLinkStatus()

    expect(result).toEqual({ ok: true, data: { linked: false } })
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['GET', '/api/staff-link/me'])
  })

  it('removes the own link with DELETE /staff-link/me', async () => {
    const { api, calls } = apiWith([{ body: { oid: OID } }])

    const result = await api.unlinkOwnStaffLink()

    expect(result.ok, 'the removal succeeded').toBe(true)
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['DELETE', '/api/staff-link/me'])
  })

  it('lists all links as plain items from GET /admin/staff-link', async () => {
    const item = { oid: OID, displayName: 'Ada', minecraftUuid: '11111111-1111-4111-8111-111111111111', linkedAt: 5 }
    const { api, calls } = apiWith([{ body: { items: [item] } }])

    const result = await api.staffLinks()

    expect(result).toEqual({ ok: true, data: [item] })
    expect(path(calls[0]!)).toBe('/api/admin/staff-link')
  })

  it('removes anyone with DELETE /admin/staff-link/{oid}', async () => {
    const { api, calls } = apiWith([{ body: { oid: OID } }])

    await api.unlinkStaffLink(OID)

    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['DELETE', `/api/admin/staff-link/${OID}`])
  })

  it('reports a 403 as FORBIDDEN', async () => {
    const { api } = apiWith([{ status: 403, body: { errorMessage: 'no', kind: 'FORBIDDEN' } }])

    const result = await api.staffLinks()

    expect(!result.ok && result.error.kind, 'a moderator is refused').toBe('FORBIDDEN')
  })
})

describe('staff actor calls', () => {
  it('lists the principals as plain items from GET /admin/staff-actor', async () => {
    const item = { oid: OID, kind: 'HUMAN', displayName: 'Ada', firstSeenAt: 1, lastSeenAt: 5 }
    const { api, calls } = apiWith([{ body: { items: [item] } }])

    const result = await api.staffActors()

    expect(result).toEqual({ ok: true, data: [item] })
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['GET', '/api/admin/staff-actor'])
  })

  it('treats an answer without items as an empty list', async () => {
    const { api } = apiWith([{ body: {} }])

    expect(await api.staffActors()).toEqual({ ok: true, data: [] })
  })

  it('anonymizes with POST /admin/staff-actor/{oid}/anonymize', async () => {
    const { api, calls } = apiWith([{ body: { oid: OID } }])

    const result = await api.anonymizeStaffActor(OID)

    expect(result.ok, 'the call succeeded').toBe(true)
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['POST', `/api/admin/staff-actor/${OID}/anonymize`])
  })

  it('reports a 404 for an unknown principal', async () => {
    const { api } = apiWith([{ status: 404, body: { errorMessage: 'there is no staff actor', kind: 'NOT_FOUND' } }])

    const result = await api.anonymizeStaffActor(OID)

    expect(result.ok ? null : result.error.kind).toBe('NOT_FOUND')
  })
})

describe('vanilla import call', () => {
  const players = new Blob(['[]'], { type: 'application/json' })
  const result = { dryRun: true, playersTotal: 0, playersImported: 0, playersSkippedExisting: 0, playersInvalid: 0, ipsTotal: 0, ipsSkipped: 0, reasonGroups: [] }

  it('posts multipart to /admin/import/vanilla with the dry run flag and the players part', async () => {
    const { api, calls } = apiWith([{ body: result }])

    const outcome = await api.importVanillaBans({ players, templateKey: 'cheating', dryRun: true })

    expect(outcome.ok, 'succeeded').toBe(true)
    expect([calls[0]!.method, path(calls[0]!), new URL(calls[0]!.url).search]).toEqual(['POST', '/api/admin/import/vanilla', '?dryRun=true&templateKey=cheating'])
    expect(calls[0]!.headers.get('content-type'), 'multipart').toContain('multipart/form-data')
    expect(calls[0]!.body, 'players part').toContain('name="bannedPlayers"')
    expect(calls[0]!.body, 'no ips part').not.toContain('bannedIps')
    expect(calls[0]!.headers.get('authorization'), 'bearer token').toBe('Bearer access-token')
  })

  it('adds the optional ips part and defaults a missing invalid list to empty', async () => {
    const { api, calls } = apiWith([{ body: result }])

    const outcome = await api.importVanillaBans({ players, ips: new Blob(['[]']), templateKey: 'cheating', dryRun: false })

    expect(calls[0]!.body, 'ips part').toContain('name="bannedIps"')
    expect(new URL(calls[0]!.url).search).toBe('?dryRun=false&templateKey=cheating')
    expect(outcome.ok && outcome.data.invalidEntries, 'invalid entries').toEqual([])
  })

  it('sends the reason mapping as a JSON part only when there is one', async () => {
    const { api, calls } = apiWith([{ body: result }, { body: result }])

    await api.importVanillaBans({ players, templateKey: 'cheating', reasonTemplates: { Griefing: 'griefing' }, dryRun: true })
    await api.importVanillaBans({ players, templateKey: 'cheating', reasonTemplates: {}, dryRun: true })

    expect(calls[0]!.body, 'mapping part').toContain('name="reasonTemplates"')
    expect(calls[0]!.body, 'mapping content').toContain('{"Griefing":"griefing"}')
    expect(calls[1]!.body, 'no empty mapping').not.toContain('reasonTemplates')
  })

  it('encodes the template key in the query and defaults missing reason groups to empty', async () => {
    const { api, calls } = apiWith([{ body: { ...result, reasonGroups: undefined } }])

    const outcome = await api.importVanillaBans({ players, templateKey: 'a b&c', dryRun: true })

    expect(new URL(calls[0]!.url).searchParams.get('templateKey'), 'decoded key').toBe('a b&c')
    expect(outcome.ok && outcome.data.reasonGroups, 'reason groups').toEqual([])
  })

  it('reports a text error body as the message', async () => {
    const { api } = apiWith([{ status: 400, body: 'Failed to read uploaded file(s): x' }])

    const outcome = await api.importVanillaBans({ players, templateKey: 'cheating', dryRun: true })

    expect(outcome.ok ? null : outcome.error).toMatchObject({ status: 400, message: 'Failed to read uploaded file(s): x' })
  })

  it('reports a network failure without throwing', async () => {
    const { api } = apiWith([new Error('offline')])

    const outcome = await api.importVanillaBans({ players, templateKey: 'cheating', dryRun: true })

    expect(outcome.ok ? null : outcome.error.kind).toBe('NETWORK')
  })
})

describe('imported punishment calls', () => {
  it('reads the groups from GET /admin/import/punishments', async () => {
    const { api, calls } = apiWith([{ body: { total: 3, groups: [{ reason: 'Hacking', count: 3 }] } }])

    const outcome = await api.importedPunishmentGroups()

    expect(outcome).toEqual({ ok: true, data: { total: 3, groups: [{ reason: 'Hacking', count: 3 }] } })
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['GET', '/api/admin/import/punishments'])
  })

  it('links a reason to a template with POST /admin/import/punishments/retemplate', async () => {
    const { api, calls } = apiWith([{ body: { templateKey: 'cheating', matched: 3, retemplated: 3, unchanged: 0, skipped: 0 } }])

    const outcome = await api.retemplateImportedPunishments({ templateKey: 'cheating', reason: 'Hacking' })

    expect(outcome.ok && outcome.data.retemplated, 'linked').toBe(3)
    expect([calls[0]!.method, path(calls[0]!)]).toEqual(['POST', '/api/admin/import/punishments/retemplate'])
    expect(JSON.parse(calls[0]!.body), 'request body').toEqual({ templateKey: 'cheating', reason: 'Hacking' })
  })

  it('reports a refused template as the backend message', async () => {
    const { api } = apiWith([{ status: 400, body: { errorMessage: 'mute section', kind: 'VALIDATION' } }])

    const outcome = await api.retemplateImportedPunishments({ templateKey: 'chat-spam', reason: 'Hacking' })

    expect(outcome.ok ? null : outcome.error).toMatchObject({ status: 400, kind: 'VALIDATION' })
  })
})
