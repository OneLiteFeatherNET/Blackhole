import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'
import type { ClientRuleDraft } from './types'

const draft: ClientRuleDraft = {
  key: ' meteor ',
  displayName: ' Meteor client ',
  kind: 'CHANNEL',
  enabled: true,
  patterns: [' meteor-client:* ', ''],
  protocolMin: 5,
  protocolMax: 9,
  negate: true,
  scope: 'GLOBAL',
  sectionKeys: ['minigames'],
  targetKind: 'CLOUDNET_TASK',
  servers: ['BedWars'],
  action: 'NOTIFY',
  denyMessage: 'ignored',
  templateKey: 'ignored',
  acknowledgedSpoofable: true,
  hideCommand: true,
}

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

const sent = (call: { body: string }) => JSON.parse(call.body) as Record<string, unknown>

describe('client rule bodies', () => {
  it('sends only the fields that belong to the kind, scope and action', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.createClientRule(draft)

    expect(calls[0]!.method).toBe('POST')
    expect(calls[0]!.url).toBe('http://ui.test/api/client-rule')
    expect(sent(calls[0]!)).toEqual({
      key: 'meteor',
      displayName: 'Meteor client',
      kind: 'CHANNEL',
      patterns: ['meteor-client:*'],
      protocolMin: null,
      protocolMax: null,
      negate: false,
      scope: 'GLOBAL',
      sectionKeys: [],
      targetKind: 'PATTERN',
      servers: [],
      action: 'NOTIFY',
      denyMessage: null,
      templateKey: null,
      acknowledgedSpoofable: false,
      hideCommand: false,
    })
  })

  it('sends the range of a protocol rule and no patterns', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.createClientRule({ ...draft, kind: 'PROTOCOL', negate: true })

    expect(sent(calls[0]!)).toMatchObject({ kind: 'PROTOCOL', patterns: [], protocolMin: 5, protocolMax: 9, negate: true })
  })

  it('keeps the sections and own servers of a server-limited rule', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.createClientRule({ ...draft, scope: 'SERVERS' })

    expect(sent(calls[0]!)).toMatchObject({ scope: 'SERVERS', sectionKeys: ['minigames'], targetKind: 'CLOUDNET_TASK', servers: ['BedWars'] })
  })

  it('sends the message of a deny rule and the template and acknowledgement of a punishing one', async () => {
    const { calls, api: client } = api([{ body: {} }, { body: {} }, { body: {} }])

    await client.createClientRule({ ...draft, action: 'DENY', denyMessage: ' No. ' })
    await client.createClientRule({ ...draft, action: 'TEMPLATE', templateKey: ' old-client ' })
    await client.createClientRule({ ...draft, kind: 'COMMAND', action: 'TEMPLATE', templateKey: 'old-client' })

    expect(sent(calls[0]!)).toMatchObject({ action: 'DENY', denyMessage: 'No.', templateKey: null })
    expect(sent(calls[1]!)).toMatchObject({ action: 'TEMPLATE', templateKey: 'old-client', denyMessage: null, acknowledgedSpoofable: true })
    expect(sent(calls[2]!)).toMatchObject({ acknowledgedSpoofable: false })
  })

  it('negates only brand and protocol rules and hides only command rules', async () => {
    const { calls, api: client } = api([{ body: {} }, { body: {} }, { body: {} }])

    await client.createClientRule({ ...draft, kind: 'BRAND' })
    await client.createClientRule({ ...draft, kind: 'MOD' })
    await client.createClientRule({ ...draft, kind: 'COMMAND' })

    expect(sent(calls[0]!)).toMatchObject({ negate: true, hideCommand: false })
    expect(sent(calls[1]!)).toMatchObject({ negate: false, hideCommand: false })
    expect(sent(calls[2]!)).toMatchObject({ negate: false, hideCommand: true })
  })

  it('updates with a PUT to the rule and sends whether it is enabled', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.updateClientRule({ ...draft, enabled: false })

    expect(calls[0]!.method).toBe('PUT')
    expect(calls[0]!.url).toBe('http://ui.test/api/client-rule/meteor')
    expect(sent(calls[0]!)).toMatchObject({ key: 'meteor', enabled: false })
  })

  it('deletes with a DELETE to the rule', async () => {
    const { calls, api: client } = api([{ body: {} }])

    await client.deleteClientRule('meteor')

    expect(calls[0]!.method).toBe('DELETE')
    expect(calls[0]!.url).toBe('http://ui.test/api/client-rule/meteor')
  })
})

describe('client rule reads', () => {
  it('fills in the lists and the count the backend leaves out', async () => {
    const { calls, api: client } = api([{ body: [{ key: 'a', displayName: 'A', kind: 'BRAND', enabled: true, negate: false, scope: 'GLOBAL', targetKind: 'PATTERN', action: 'NOTIFY' }] }])

    const result = await client.clientRules()

    expect(calls[0]!.url).toBe('http://ui.test/api/client-rule')
    expect(result.ok && result.data[0]).toMatchObject({ patterns: [], sectionKeys: [], servers: [], hitCount: 0 })
  })

  it('reads the hits of a rule and of a player as lists', async () => {
    const hits = [{ rule: 'a', owner: 'ab'.repeat(64), at: '2026-10-24T10:00:00Z', action: 'NOTIFY', enforced: false, bypassed: false }]
    const { calls, api: client } = api([{ body: { hits } }, { body: { hits } }, { body: {} }])

    const ofRule = await client.clientRuleHits('a')
    const ofOwner = await client.clientRuleHitsOfOwner('ab'.repeat(64))
    const none = await client.clientRuleHits('a')

    expect(calls[0]!.url).toBe('http://ui.test/api/client-rule/a/hits')
    expect(calls[1]!.url).toBe(`http://ui.test/api/client-rule/hits?owner=${'ab'.repeat(64)}`)
    expect(ofRule).toEqual({ ok: true, data: hits })
    expect(ofOwner).toEqual({ ok: true, data: hits })
    expect(none).toEqual({ ok: true, data: [] })
  })

  it('turns lists the catalogue leaves out into empty lists', async () => {
    const { calls, api: client } = api([{ body: { available: false } }])

    const result = await client.clientRuleCatalogue()

    expect(calls[0]!.url).toBe('http://ui.test/api/client-rule/catalogue')
    expect(result).toEqual({ ok: true, data: { available: false, brands: [], channels: [], commands: [], protocols: [], proxies: [] } })
  })

  it('reports a failing request as an error instead of throwing', async () => {
    const { api: client } = api([{ status: 409, body: { errorMessage: 'a rule with key x already exists', kind: 'CONFLICT' } }])

    const result = await client.createClientRule(draft)

    expect(result.ok).toBe(false)
  })
})
