import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

describe('configuration items read by their address', () => {
  it('reads one template by its identifier', async () => {
    const { calls, api: client } = api([{ body: { identifier: 'id-1', key: 'hacking', reason: 'Hacking', steps: [] } }])

    const result = await client.template('id-1')

    expect(calls[0]!.method).toBe('GET')
    expect(calls[0]!.url).toBe('http://ui.test/api/template/id-1')
    expect(result.ok && result.data.key).toBe('hacking')
  })

  it('gives a template without steps an empty step list', async () => {
    const { api: client } = api([{ body: { identifier: 'id-1', key: 'hacking', reason: 'Hacking' } }])

    const result = await client.template('id-1')

    expect(result.ok && result.data.steps).toEqual([])
  })

  it('reads one section by its key', async () => {
    const { calls, api: client } = api([{ body: { key: 'chat', displayName: 'Chat' } }])

    const result = await client.section('chat')

    expect(calls[0]!.url).toBe('http://ui.test/api/section/chat')
    expect(result.ok && result.data.servers).toEqual([])
  })

  it('reads one client rule by its key and fills in its empty lists', async () => {
    const { calls, api: client } = api([{ body: { key: 'meteor', displayName: 'Meteor', enabled: true } }])

    const result = await client.clientRule('meteor')

    expect(calls[0]!.url).toBe('http://ui.test/api/client-rule/meteor')
    expect(result.ok && result.data.patterns).toEqual([])
  })

  it('reads one appeal conditions version by its number', async () => {
    const { calls, api: client } = api([{ body: { version: 3, text: 'Terms', createdAt: '2026-10-01T10:00:00Z' } }])

    const result = await client.appealConditionVersion(3)

    expect(calls[0]!.url).toBe('http://ui.test/api/appeal-conditions/versions/3')
    expect(result.ok && result.data.text).toBe('Terms')
  })

  it('reports a template that does not exist as not found', async () => {
    const { api: client } = api([{ status: 404, body: {} }])

    const result = await client.template('missing')

    expect(result.ok).toBe(false)
    expect(!result.ok && result.error.kind).toBe('NOT_FOUND')
  })
})
