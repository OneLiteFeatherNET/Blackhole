import { describe, expect, it } from 'vitest'
import type { ApiError, IpBan } from '../../api/types'
import { banState, checkAddress, expiresAtFor, ipBanErrorKey, MODERATOR_MAX_SECONDS, validateIpBanForm, validateLiftReason } from './ipBanRules'

const DAY_MS = 86_400_000

describe('checkAddress', () => {
  it.each(['203.0.113.7', '2001:db8::1', '::1', '::ffff:203.0.113.7', '203.0.113.0/24', '2001:db8::/32', '2001:db8:0:0:0:0:0:1'])(
    'accepts %s',
    (address) => {
      expect(checkAddress(address)).toEqual({ ok: true, address })
    },
  )

  it('trims the input before checking it', () => {
    expect(checkAddress('  203.0.113.7 ')).toEqual({ ok: true, address: '203.0.113.7' })
  })

  it('reports an empty field as empty', () => {
    expect(checkAddress('   ')).toEqual({ ok: false, reason: 'empty' })
  })

  it.each(['256.1.1.1', '203.0.113', '2001:db8::1::2', 'example.org', '203.0.113.0/33', '2001:db8::/129', '203.0.113.0/', '2001:xyz::1'])(
    'refuses %s as not an address or range',
    (address) => {
      expect(checkAddress(address)).toEqual({ ok: false, reason: 'invalid' })
    },
  )

  it('refuses an IPv4 range wider than /16 as too wide', () => {
    expect(checkAddress('203.0.0.0/8')).toEqual({ ok: false, reason: 'tooWide' })
  })

  it('accepts an IPv4 range of exactly /16', () => {
    expect(checkAddress('203.0.0.0/16')).toEqual({ ok: true, address: '203.0.0.0/16' })
  })

  it('refuses an IPv6 range wider than /32 as too wide', () => {
    expect(checkAddress('2001::/16')).toEqual({ ok: false, reason: 'tooWide' })
  })
})

describe('validateIpBanForm', () => {
  const valid = { address: '203.0.113.7', reason: 'Ban evasion', duration: '7d', permanent: false }

  it('accepts a temporary ban of a valid address for a moderator', () => {
    expect(validateIpBanForm(valid, { administrator: false })).toEqual({})
  })

  it('reports the address when it is not valid', () => {
    expect(validateIpBanForm({ ...valid, address: '999.1.1.1' }, { administrator: false })).toMatchObject({ address: 'invalid' })
  })

  it('requires a reason', () => {
    expect(validateIpBanForm({ ...valid, reason: '  ' }, { administrator: false })).toMatchObject({ reason: 'empty' })
  })

  it('refuses a reason longer than 200 characters', () => {
    expect(validateIpBanForm({ ...valid, reason: 'x'.repeat(201) }, { administrator: false })).toMatchObject({ reason: 'tooLong' })
  })

  it('requires a duration for a moderator who does not choose permanent', () => {
    expect(validateIpBanForm({ ...valid, duration: '' }, { administrator: false })).toMatchObject({ duration: 'empty' })
  })

  it('refuses a duration the parser cannot read', () => {
    expect(validateIpBanForm({ ...valid, duration: '3' }, { administrator: false })).toMatchObject({ duration: 'missingUnit' })
  })

  it('accepts a duration of exactly the moderator cap', () => {
    expect(validateIpBanForm({ ...valid, duration: '30d' }, { administrator: false })).toEqual({})
  })

  it('refuses a moderator duration above the 30 day cap', () => {
    expect(validateIpBanForm({ ...valid, duration: '31d' }, { administrator: false })).toMatchObject({ duration: 'tooLong' })
  })

  it('does not cap the duration for an administrator', () => {
    expect(validateIpBanForm({ ...valid, duration: '90d' }, { administrator: true })).toEqual({})
  })

  it('refuses a permanent ban for a moderator', () => {
    expect(validateIpBanForm({ ...valid, permanent: true }, { administrator: false })).toMatchObject({ permanent: 'notAllowed' })
  })

  it('needs no duration for a permanent ban by an administrator', () => {
    expect(validateIpBanForm({ ...valid, duration: '', permanent: true }, { administrator: true })).toEqual({})
  })

  it('caps the moderator duration at 30 days', () => {
    expect(MODERATOR_MAX_SECONDS).toBe(30 * 86_400)
  })
})

describe('expiresAtFor', () => {
  it('adds the duration to the given instant', () => {
    expect(expiresAtFor({ duration: '1d', permanent: false }, 1_000)).toBe(1_000 + DAY_MS)
  })

  it('is null for a permanent ban', () => {
    expect(expiresAtFor({ duration: '', permanent: true }, 1_000)).toBeNull()
  })
})

describe('validateLiftReason', () => {
  it('requires a reason that is not blank', () => {
    expect(validateLiftReason('   ')).toBe('empty')
  })

  it('refuses a reason longer than 200 characters', () => {
    expect(validateLiftReason('x'.repeat(201))).toBe('tooLong')
  })

  it('accepts a reason of 200 characters', () => {
    expect(validateLiftReason('x'.repeat(200))).toBeNull()
  })
})

describe('ipBanErrorKey', () => {
  const error = (status: number, code?: string): ApiError => ({ status, message: 'server text', kind: 'VALIDATION', ...(code ? { code } : {}) })

  it.each([
    ['IPBAN_PROTECTED', 'protected'],
    ['IPBAN_RANGE_TOO_WIDE', 'tooWide'],
    ['FORBIDDEN_DURATION', 'forbiddenDuration'],
    ['ALREADY_BANNED', 'alreadyBanned'],
  ])('maps the server code %s to the %s message', (code, key) => {
    expect(ipBanErrorKey(error(422, code), 'create')).toBe(key)
  })

  it('reads a conflict on lift as "not active" when the server gives no code', () => {
    expect(ipBanErrorKey(error(409), 'lift')).toBe('notActive')
  })

  it('has no message of its own for an unknown code, so the server text is shown', () => {
    expect(ipBanErrorKey(error(500, 'SOMETHING_NEW'), 'create')).toBeNull()
  })
})

describe('banState', () => {
  const now = 10 * DAY_MS
  const ban = (overrides: Partial<IpBan>): IpBan => ({
    id: 'b1',
    range: '203.0.113.7',
    reason: 'x',
    source: 'PANEL',
    createdAt: 0,
    warnings: [],
    ...overrides,
  })

  it('is active when not lifted and not expired', () => {
    expect(banState(ban({ expiresAt: now + DAY_MS }), now)).toBe('active')
  })

  it('is active when it has no expiry', () => {
    expect(banState(ban({}), now)).toBe('active')
  })

  it('is lifted once it has a lift time', () => {
    expect(banState(ban({ liftedAt: now - 1 }), now)).toBe('lifted')
  })

  it('is expired once its expiry has passed', () => {
    expect(banState(ban({ expiresAt: now }), now)).toBe('expired')
  })
})
