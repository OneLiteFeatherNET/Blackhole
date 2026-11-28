import { describe, expect, it } from 'vitest'
import { CASE_STATUS_TONE, PRIORITY_TONE, PUNISHMENT_STATE_TONE } from './caseTones'

describe('case status tones', () => {
  it.each([
    ['OPEN', 'primary'],
    ['IN_REVIEW', 'tertiary'],
    ['WAITING', 'warning'],
    ['CLOSED', 'neutral'],
  ] as const)('gives the case status %s the %s tone', (status, tone) => {
    expect(CASE_STATUS_TONE[status]).toBe(tone)
  })
})

describe('punishment state tones', () => {
  it.each([
    ['ACTIVE', 'error'],
    ['EXPIRED', 'neutral'],
    ['LIFTED', 'success'],
  ] as const)('gives the punishment state %s the %s tone', (state, tone) => {
    expect(PUNISHMENT_STATE_TONE[state]).toBe(tone)
  })

  it('uses the error tone only for an active punishment', () => {
    const errorStates = Object.entries(PUNISHMENT_STATE_TONE).filter(([, tone]) => tone === 'error')
    expect(errorStates.map(([state]) => state)).toEqual(['ACTIVE'])
  })
})

describe('error tone is reserved', () => {
  it('uses the error tone for no case status', () => {
    expect(Object.values(CASE_STATUS_TONE)).not.toContain('error')
  })
})

describe('priority tones', () => {
  it.each([
    ['LOW', 'neutral'],
    ['NORMAL', 'outline'],
    ['HIGH', 'warning'],
    ['URGENT', 'error'],
  ] as const)('gives the priority %s the %s tone', (priority, tone) => {
    expect(PRIORITY_TONE[priority]).toBe(tone)
  })

  it('gives High and Urgent a tone that Low and Normal do not share', () => {
    expect(PRIORITY_TONE.HIGH).not.toBe(PRIORITY_TONE.NORMAL)
    expect(PRIORITY_TONE.URGENT).not.toBe(PRIORITY_TONE.LOW)
  })
})
