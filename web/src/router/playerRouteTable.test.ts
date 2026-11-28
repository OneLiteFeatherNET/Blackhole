import { describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from './index'

// The values that must never appear in an address (decisions 3 and 8 of restructure-staff-panel). They are fixtures:
// a player name, a raw player UUID, an IP address and a Ban ID, each recognisable in a failing assertion.
const OWNER = 'ab'.repeat(64)
const SHORT_ID = 'A1B2C3D4'
const NAME = 'Steve'
const RAW_UUID = '11111111-2222-3333-4444-555555555555'
const IP = '203.0.113.7'
const BAN_ID = '9f8e7d6c-5b4a-4321-8765-0fedcba98765'

const ACTION_ROUTES = [
  { name: 'player-punish', path: `/players/${OWNER}/punish` },
  { name: 'player-warn', path: `/players/${OWNER}/warn` },
  { name: 'player-adjust', path: `/players/${OWNER}/elo/adjust?section=network` },
  { name: 'player-kick', path: `/players/${OWNER}/kick` },
  { name: 'punished-revoke', path: `/punished/${SHORT_ID}/revoke?owner=${OWNER}` },
]

function router() {
  return createAppRouter(createMemoryHistory())
}

describe('the player and punishment addresses', () => {
  it.each([
    [`/players/${OWNER}`, 'player'],
    [`/players/${OWNER}?tab=evasion`, 'player'],
    [`/players/${OWNER}/kick`, 'player-kick'],
    ['/punished', 'punished'],
    [`/punished/${SHORT_ID}`, 'punished-detail'],
    [`/punished/${SHORT_ID}?tab=history&owner=${OWNER}`, 'punished-detail'],
    [`/punished/${SHORT_ID}/revoke?owner=${OWNER}`, 'punished-revoke'],
  ])('resolves %s to the route %s', (path, name) => {
    expect(router().resolve(path).name).toBe(name)
  })

  it('has no route for a warning by its id, because a warning id is a Ban ID', () => {
    expect(router().resolve(`/warnings/${BAN_ID}`).name).toBe('not-found')
  })

  it('keeps the short id of a punishment in the path without the hash', () => {
    expect(router().resolve({ name: 'punished-detail', params: { shortId: SHORT_ID } }).fullPath).toBe(`/punished/${SHORT_ID}`)
  })
})

describe('the moderator-only routes', () => {
  it.each(ACTION_ROUTES)('marks $name as a route for moderators', ({ name, path }) => {
    const route = router().resolve(path).matched.at(-1)

    expect(route?.name).toBe(name)
    expect(route?.meta.moderator, `${name} needs the moderator flag`).toBe(true)
  })

  it('does not mark the read routes for moderators only', () => {
    for (const path of [`/players/${OWNER}`, `/punished/${SHORT_ID}`]) {
      expect(router().resolve(path).matched.at(-1)?.meta.moderator, path).toBeUndefined()
    }
  })
})

describe('the addresses of the player and punishment pages', () => {
  it('never hold a player name, a raw UUID, an IP address or a Ban ID', () => {
    const r = router()
    const addresses = [
      r.resolve({ name: 'player', params: { owner: OWNER }, query: { tab: 'names' } }).fullPath,
      r.resolve({ name: 'player-punish', params: { owner: OWNER } }).fullPath,
      r.resolve({ name: 'player-adjust', params: { owner: OWNER }, query: { section: 'chat' } }).fullPath,
      r.resolve({ name: 'punished-detail', params: { shortId: SHORT_ID }, query: { owner: OWNER, tab: 'evidence' } }).fullPath,
      r.resolve({ name: 'punished-revoke', params: { shortId: SHORT_ID }, query: { owner: OWNER } }).fullPath,
      r.resolve({ name: 'punished', query: { kind: 'BAN' } }).fullPath,
    ]

    for (const address of addresses) {
      for (const secret of [NAME, RAW_UUID, IP, BAN_ID]) {
        expect(address, `${secret} in ${address}`).not.toContain(secret)
      }
    }
  })
})
