import { describe, expect, it } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import { fakeFetch } from '../test/fakeFetch'
import { createBlackholeApi } from './blackhole'

function api(responses: Parameters<typeof fakeFetch>[0]) {
  const { calls, impl } = fakeFetch(responses)
  return { calls, api: createBlackholeApi({ baseUrl: 'http://ui.test/api', auth: fakeHttpAuth().auth, fetch: impl }) }
}

function cases(count: number) {
  return Array.from({ length: count }, (_, index) => ({ punishmentIdentifier: `p-${index}`, expectedVersion: 2 }))
}

/** A server answer for one chunk: every case of the request is UPDATED. */
function answerFor(chunk: { punishmentIdentifier: string; expectedVersion: number }[]) {
  return {
    batchId: 'b-1',
    updated: chunk.length,
    unchanged: 0,
    conflicts: 0,
    notFound: 0,
    failed: 0,
    items: chunk.map((c) => ({ punishmentIdentifier: c.punishmentIdentifier, outcome: 'UPDATED', changed: true, version: 3, auditWritten: true })),
  }
}

describe('bulk case edit', () => {
  it('posts the shared change with each case and its expected version, and sends the cleared assignee as a flag', async () => {
    const { calls, api: client } = api([{ body: answerFor(cases(2)) }])

    const result = await client.bulkEditCases({ cases: cases(2), status: 'CLOSED', assigneeOid: null, addTags: ['spam'] })

    expect(result.ok).toBe(true)
    expect(calls).toHaveLength(1)
    expect(new URL(calls[0]!.url).pathname).toBe('/api/case/bulk')
    expect(calls[0]!.method).toBe('POST')
    expect(JSON.parse(calls[0]!.body)).toEqual({
      cases: [
        { punishmentIdentifier: 'p-0', expectedVersion: 2 },
        { punishmentIdentifier: 'p-1', expectedVersion: 2 },
      ],
      status: 'CLOSED',
      clearAssignee: true,
      addTags: ['spam'],
    })
  })

  it('sends a list longer than one request in chunks of 100 and 50, in order, and reports the progress of each', async () => {
    const all = cases(150)
    const { calls, api: client } = api([{ body: answerFor(all.slice(0, 100)) }, { body: answerFor(all.slice(100)) }])
    const progress: Array<[number, number]> = []

    const result = await client.bulkEditCases({ cases: all, priority: 'HIGH' }, {
      onProgress: (done, total) => progress.push([done, total]),
    })

    expect(calls).toHaveLength(2)
    expect(JSON.parse(calls[0]!.body).cases).toHaveLength(100)
    expect(JSON.parse(calls[1]!.body).cases).toHaveLength(50)
    expect(JSON.parse(calls[1]!.body).cases[0].punishmentIdentifier).toBe('p-100')
    expect(progress).toEqual([
      [100, 150],
      [150, 150],
    ])
    expect(result.ok && result.data).toHaveLength(150)
  })

  it('marks every case of a chunk the server refused as FAILED with the message, and goes on with the next chunk', async () => {
    const all = cases(101)
    const { api: client } = api([
      { status: 400, body: { errorMessage: 'a tag is not valid', kind: 'VALIDATION' } },
      { body: answerFor(all.slice(100)) },
    ])

    const result = await client.bulkEditCases({ cases: all, status: 'OPEN' })

    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.data[0]).toMatchObject({ punishmentIdentifier: 'p-0', outcome: 'FAILED', changed: false, message: 'a tag is not valid' })
    expect(result.data[99]!.outcome).toBe('FAILED')
    expect(result.data[100]).toMatchObject({ punishmentIdentifier: 'p-100', outcome: 'UPDATED' })
  })
})
