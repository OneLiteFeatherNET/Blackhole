import { describe, expect, it } from 'vitest'
import { appealStatePill, caseStatePill, playerStatePill, reportStatePill } from './entityState'

describe('the state pill of a case', () => {
  it.each([
    ['OPEN', 'primary'],
    ['IN_REVIEW', 'tertiary'],
    ['WAITING', 'warning'],
    ['CLOSED', 'neutral'],
  ] as const)('shows %s as a text pill in its tone %s', (status, tone) => {
    expect(caseStatePill(status)).toEqual({ key: `entity.state.case.${status}`, tone })
  })
})

describe('the state pill of an appeal', () => {
  it.each([
    ['SUBMITTED', 'tertiary'],
    ['ELIGIBLE_PENDING_REVIEW', 'tertiary'],
    ['IN_REVIEW', 'tertiary'],
    ['GRANTED_FULL_LIFT', 'success'],
    ['GRANTED_DURATION_REDUCTION', 'success'],
    ['DENIED', 'neutral'],
    ['INELIGIBLE', 'neutral'],
  ] as const)('shows %s as a text pill in its tone %s', (status, tone) => {
    expect(appealStatePill(status)).toEqual({ key: `entity.state.appeal.${status}`, tone })
  })
})

describe('the state pill of a report', () => {
  it.each([
    ['OPEN', 'tertiary'],
    ['UNDER_REVIEW', 'tertiary'],
    ['ACTIONED', 'neutral'],
    ['DISMISSED', 'neutral'],
  ] as const)('shows %s as a text pill in its tone %s', (status, tone) => {
    expect(reportStatePill(status)).toEqual({ key: `entity.state.report.${status}`, tone })
  })

  it('treats a report without a status as open', () => {
    expect(reportStatePill(null)).toEqual({ key: 'entity.state.report.OPEN', tone: 'tertiary' })
  })
})

describe('the state pill of a player', () => {
  it('shows a player with a punishment in force as punished', () => {
    expect(playerStatePill(2)).toEqual({ key: 'entity.state.player.ACTIVE', tone: 'error' })
  })

  it('shows a player without one as not punished', () => {
    expect(playerStatePill(0)).toEqual({ key: 'entity.state.player.NONE', tone: 'neutral' })
  })
})
