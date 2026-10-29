import { describe, expect, it } from 'vitest'
import { durationChoices, durationSecondsOf, kindChoices, PERMANENT, reasonIsValid, valueIsValid } from './areaBanRules'

const SEVEN_DAYS = 7 * 24 * 60 * 60

describe('area ban kinds', () => {
  it('offers only ASN bans to a moderator', () => {
    expect(kindChoices(false)).toEqual(['ASN'])
  })

  it('offers country bans to an admin', () => {
    expect(kindChoices(true)).toEqual(['ASN', 'COUNTRY'])
  })
})

describe('area ban durations', () => {
  it('offers no permanent ban to a moderator', () => {
    expect(durationChoices(false)).not.toContain(PERMANENT)
  })

  it('offers no duration above seven days to a moderator', () => {
    const seconds = durationChoices(false).map(Number)

    expect(Math.max(...seconds)).toBe(SEVEN_DAYS)
  })

  it('offers a permanent ban to an admin', () => {
    expect(durationChoices(true)).toContain(PERMANENT)
  })

  it('sends no duration for a permanent ban', () => {
    expect(durationSecondsOf(PERMANENT)).toBeNull()
  })

  it('sends the seconds of a timed ban', () => {
    expect(durationSecondsOf(String(SEVEN_DAYS))).toBe(SEVEN_DAYS)
  })
})

describe('area ban value', () => {
  it.each(['1234', 'AS1234', 'as1234'])('accepts the ASN %s', (value) => {
    expect(valueIsValid('ASN', value)).toBe(true)
  })

  it.each(['', 'AS', 'ASN-1', '12 34'])('refuses the ASN "%s"', (value) => {
    expect(valueIsValid('ASN', value)).toBe(false)
  })

  it('accepts a two letter country code', () => {
    expect(valueIsValid('COUNTRY', 'DE')).toBe(true)
  })

  it.each(['D', 'DEU', '12'])('refuses the country "%s"', (value) => {
    expect(valueIsValid('COUNTRY', value)).toBe(false)
  })
})

describe('area ban reason', () => {
  it('refuses a blank reason', () => {
    expect(reasonIsValid('   ')).toBe(false)
  })

  it('accepts a reason of 200 characters', () => {
    expect(reasonIsValid('x'.repeat(200))).toBe(true)
  })

  it('refuses a reason longer than 200 characters', () => {
    expect(reasonIsValid('x'.repeat(201))).toBe(false)
  })
})
